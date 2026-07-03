#include <jni.h>
#include <Python.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <signal.h>
#include <setjmp.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <dirent.h>
#include <pthread.h>
#include <fcntl.h>
#include <unistd.h>

#define LOG_TAG "PythonBridge-C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int python_initialized = 0;
static char last_error[2048] = "";
static JavaVM *cached_jvm = NULL;

// 信号保护: 用于在 Py_Initialize 崩溃时恢复
static sigjmp_buf init_jmp;
static volatile int init_in_progress = 0;

static void crash_handler(int sig) {
    LOGE("crash_handler: 收到信号 %d", sig);
    if (init_in_progress) {
        siglongjmp(init_jmp, sig);
    }
    // 不在初始化阶段，恢复默认处理
    signal(sig, SIG_DFL);
    raise(sig);
}

static void log_check_dir(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            DIR *d = opendir(path);
            int count = 0;
            if (d) {
                struct dirent *ent;
                while ((ent = readdir(d)) != NULL) count++;
                closedir(d);
            }
            LOGI("  [目录存在] %s (含 %d 项)", path, count);
        } else {
            LOGI("  [文件存在] %s (%ld bytes)", path, (long)st.st_size);
        }
    } else {
        LOGE("  [不存在] %s (errno=%d: %s)", path, errno, strerror(errno));
    }
}

static void ensure_std_fds() {
    int fd;
    if ((fd = open("/dev/null", O_RDWR)) >= 0) {
        if (dup2(fd, STDIN_FILENO) < 0 && errno != EBADF)
            LOGE("ensure_std_fds: dup2(stdin) 失败: %s", strerror(errno));
        if (dup2(fd, STDOUT_FILENO) < 0 && errno != EBADF)
            LOGE("ensure_std_fds: dup2(stdout) 失败: %s", strerror(errno));
        if (dup2(fd, STDERR_FILENO) < 0 && errno != EBADF)
            LOGE("ensure_std_fds: dup2(stderr) 失败: %s", strerror(errno));
        if (fd > STDERR_FILENO) close(fd);
        LOGI("ensure_std_fds: stdin=%d stdout=%d stderr=%d",
             fcntl(STDIN_FILENO, F_GETFD), fcntl(STDOUT_FILENO, F_GETFD), fcntl(STDERR_FILENO, F_GETFD));
    } else {
        LOGE("ensure_std_fds: 打开 /dev/null 失败: %s", strerror(errno));
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("JNI_OnLoad: libpython_bridge.so 已加载");
    cached_jvm = vm;

    void *handle = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle) {
        LOGI("JNI_OnLoad: libpython3.14.so 预加载成功");
    } else {
        LOGE("JNI_OnLoad: libpython3.14.so 预加载失败: %s", dlerror());
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeGetLastError(
    JNIEnv *env, jobject obj
) {
    return (*env)->NewStringUTF(env, last_error);
}

/**
 * 安全测试初始化: 检测 Py_Initialize 是否会崩溃
 * 返回: 0=可初始化, -1=崩溃, -2=错误
 */
JNIEXPORT jint JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeTestInit(
    JNIEnv *env, jobject obj, jstring home
) {
    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    LOGI("nativeTestInit: PYTHONHOME=%s", home_utf8);

    // 安装信号处理器
    struct sigaction sa;
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);

    // 设置环境
    setenv("PYTHONHOME", home_utf8, 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    setenv("PYTHONEXECUTABLE", "/usr/bin/python3", 1);

    // 禁用 _android_support.py
    char as_path[512], as_bak[520];
    snprintf(as_path, sizeof(as_path), "%s/lib/python3.14/_android_support.py", home_utf8);
    snprintf(as_bak, sizeof(as_bak), "%s.bak", as_path);
    if (rename(as_path, as_bak) == 0) {
        LOGI("nativeTestInit: 已禁用 _android_support.py");
    }

    ensure_std_fds();

    // 预初始化
    PyPreConfig preconfig;
    PyPreConfig_InitPythonConfig(&preconfig);
    preconfig.utf8_mode = 1;
    preconfig.allocator = PYMEM_ALLOCATOR_MALLOC;

    PyStatus status = Py_PreInitialize(&preconfig);
    if (PyStatus_Exception(status)) {
        snprintf(last_error, sizeof(last_error), "Py_PreInitialize 失败: %s",
                 status.err_msg ? status.err_msg : "unknown");
        LOGE("nativeTestInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -2;
    }
    LOGI("nativeTestInit: Py_PreInitialize 成功");

    // 用 setjmp 保护 Py_Initialize
    init_in_progress = 1;
    int crash_sig = sigsetjmp(init_jmp, 1);

    if (crash_sig != 0) {
        // 崩溃了
        init_in_progress = 0;
        snprintf(last_error, sizeof(last_error),
                 "Py_Initialize 崩溃 (信号=%d)", crash_sig);
        LOGE("nativeTestInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);

        // 恢复默认信号处理
        signal(SIGSEGV, SIG_DFL);
        signal(SIGABRT, SIG_DFL);
        signal(SIGBUS, SIG_DFL);
        return -1;
    }

    LOGI("nativeTestInit: Py_Initialize...");
    Py_Initialize();
    LOGI("nativeTestInit: Py_Initialize 返回");

    init_in_progress = 0;

    // 恢复默认信号处理
    signal(SIGSEGV, SIG_DFL);
    signal(SIGABRT, SIG_DFL);
    signal(SIGBUS, SIG_DFL);

    if (!Py_IsInitialized()) {
        snprintf(last_error, sizeof(last_error), "Py_Initialize 失败: 未初始化");
        LOGE("nativeTestInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -2;
    }

    LOGI("nativeTestInit: 初始化成功!");
    python_initialized = 1;
    PyGILState_Ensure();

    // 验证基本模块
    PyObject *enc = PyImport_ImportModule("encodings");
    if (enc) {
        Py_DECREF(enc);
        LOGI("nativeTestInit: encodings 验证成功");
    } else {
        LOGE("nativeTestInit: encodings 导入失败");
        PyErr_Clear();
    }

    (*env)->ReleaseStringUTFChars(env, home, home_utf8);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeInit(
    JNIEnv *env, jobject obj, jstring home
) {
    if (python_initialized) {
        LOGI("nativeInit: 已初始化");
        return 0;
    }

    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    LOGI("nativeInit: PYTHONHOME=%s", home_utf8);

    // 详细目录诊断
    LOGI("nativeInit: === 目录存在性检查 ===");
    log_check_dir(home_utf8);

    char buf[512];
    snprintf(buf, sizeof(buf), "%s/lib", home_utf8); log_check_dir(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14", home_utf8); log_check_dir(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/encodings", home_utf8); log_check_dir(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/encodings/__init__.py", home_utf8); log_check_dir(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/os.py", home_utf8); log_check_dir(buf);
    LOGI("nativeInit: === 目录检查结束 ===");

    // 加载 libpython3.14.so
    void *handle = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        snprintf(last_error, sizeof(last_error), "dlopen libpython3.14.so 失败: %s", dlerror());
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -1;
    }
    LOGI("nativeInit: libpython3.14.so 已加载");

    // Android 修复: 确保 fd 0/1/2 存在
    ensure_std_fds();

    // 禁用 _android_support.py 避免初始化崩溃
    {
        char android_support_path[512];
        char android_support_bak[520];
        snprintf(android_support_path, sizeof(android_support_path),
                 "%s/lib/python3.14/_android_support.py", home_utf8);
        snprintf(android_support_bak, sizeof(android_support_bak),
                 "%s.bak", android_support_path);
        if (rename(android_support_path, android_support_bak) == 0) {
            LOGI("nativeInit: 已禁用 _android_support.py -> .bak");
        } else {
            LOGI("nativeInit: _android_support.py 重命名跳过 (errno=%d: %s)", errno, strerror(errno));
        }
    }

    // 设置环境变量
    setenv("PYTHONHOME", home_utf8, 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    setenv("PYTHONEXECUTABLE", "/usr/bin/python3", 1);

    // 预初始化
    LOGI("nativeInit: Py_PreInitialize (python config)...");
    PyPreConfig preconfig;
    PyPreConfig_InitPythonConfig(&preconfig);
    preconfig.utf8_mode = 1;
    preconfig.allocator = PYMEM_ALLOCATOR_MALLOC;

    PyStatus status = Py_PreInitialize(&preconfig);
    if (PyStatus_Exception(status)) {
        snprintf(last_error, sizeof(last_error),
                 "Py_PreInitialize 失败: %s",
                 status.err_msg ? status.err_msg : "unknown");
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -2;
    }
    LOGI("nativeInit: Py_PreInitialize 成功");

    // 安装信号处理器保护 Py_Initialize
    struct sigaction sa;
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);

    init_in_progress = 1;
    int crash_sig = sigsetjmp(init_jmp, 1);

    if (crash_sig != 0) {
        init_in_progress = 0;
        snprintf(last_error, sizeof(last_error),
                 "Py_Initialize 崩溃 (信号=%d)。可能是 _android_support 或 stdio 初始化问题。",
                 crash_sig);
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        signal(SIGSEGV, SIG_DFL);
        signal(SIGABRT, SIG_DFL);
        signal(SIGBUS, SIG_DFL);
        return -3;
    }

    LOGI("nativeInit: Py_Initialize...");
    Py_Initialize();
    LOGI("nativeInit: Py_Initialize 返回");

    init_in_progress = 0;
    signal(SIGSEGV, SIG_DFL);
    signal(SIGABRT, SIG_DFL);
    signal(SIGBUS, SIG_DFL);

    if (!Py_IsInitialized()) {
        snprintf(last_error, sizeof(last_error),
                 "Py_Initialize 失败: 进程可能已崩溃 (PYTHONHOME=%s)", home_utf8);
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -3;
    }

    LOGI("nativeInit: Py_Initialize 成功!");
    (*env)->ReleaseStringUTFChars(env, home, home_utf8);

    PyGILState_Ensure();

    // 验证 encodings 模块
    PyObject *encodings_mod = PyImport_ImportModule("encodings");
    if (encodings_mod) {
        Py_DECREF(encodings_mod);
        LOGI("nativeInit: encodings 模块验证成功");
    } else {
        LOGE("nativeInit: encodings 模块导入失败");
        PyErr_Print();
        PyErr_Clear();
    }

    python_initialized = 1;
    LOGI("nativeInit: Python 完全初始化成功!");
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeIsInitialized(
    JNIEnv *env, jobject obj
) {
    return python_initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeExec(
    JNIEnv *env, jobject obj, jstring code
) {
    if (!python_initialized) {
        return (*env)->NewStringUTF(env, "错误: Python 未初始化。请重启应用或检查 logcat (PythonBridge-C) 了解初始化失败原因");
    }

    PyGILState_STATE gstate = PyGILState_Ensure();

    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);
    LOGI("nativeExec: 执行代码 (长度=%d)", (int)strlen(code_utf8));

    PyObject *main_module = PyImport_AddModule("__main__");
    PyObject *globals = PyModule_GetDict(main_module);

    PyObject *code_obj = PyUnicode_FromString(code_utf8);
    PyDict_SetItemString(globals, "_user_code", code_obj);
    Py_DECREF(code_obj);

    const char *wrapper =
        "import sys, io, traceback\n"
        "_old_stdout = sys.stdout\n"
        "_old_stderr = sys.stderr\n"
        "sys.stdout = io.StringIO()\n"
        "sys.stderr = io.StringIO()\n"
        "_result = ''\n"
        "_error = ''\n"
        "try:\n"
        "    exec(compile(_user_code, '<agent>', 'exec'))\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue()\n"
        "except SystemExit as e:\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue()\n"
        "except Exception:\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue() + traceback.format_exc()\n"
        "finally:\n"
        "    sys.stdout = _old_stdout\n"
        "    sys.stderr = _old_stderr\n";

    PyObject *result = PyRun_String(wrapper, Py_file_input, globals, globals);

    jstring ret;
    if (result == NULL) {
        PyObject *err = PyErr_Occurred();
        if (err) {
            PyObject *type, *value, *tb;
            PyErr_Fetch(&type, &value, &tb);
            PyErr_NormalizeException(&type, &value, &tb);
            PyObject *str = value ? PyObject_Str(value) : NULL;
            const char *err_str = str ? PyUnicode_AsUTF8(str) : "未知 Python 错误";
            char buf[4096];
            snprintf(buf, sizeof(buf), "Python 异常: %s", err_str);
            ret = (*env)->NewStringUTF(env, buf);
            LOGE("nativeExec: %s", buf);
            Py_XDECREF(str);
            Py_XDECREF(type);
            Py_XDECREF(value);
            Py_XDECREF(tb);
            PyErr_Clear();
        } else {
            ret = (*env)->NewStringUTF(env, "Python 执行异常（无错误信息）");
        }
    } else {
        Py_DECREF(result);

        PyObject *out = PyDict_GetItemString(globals, "_result");
        PyObject *err = PyDict_GetItemString(globals, "_error");

        const char *out_str = out ? PyUnicode_AsUTF8(out) : "";
        const char *err_str = err ? PyUnicode_AsUTF8(err) : "";

        char buf[65536];
        int offset = 0;

        if (out_str && strlen(out_str) > 0) {
            offset += snprintf(buf + offset, sizeof(buf) - offset, "%s", out_str);
        }
        if (err_str && strlen(err_str) > 0) {
            offset += snprintf(buf + offset, sizeof(buf) - offset, "[stderr]\n%s", err_str);
        }

        if (offset == 0) {
            ret = (*env)->NewStringUTF(env, "(无输出)");
        } else {
            ret = (*env)->NewStringUTF(env, buf);
        }
        LOGI("nativeExec: 成功，输出长度=%d", offset);
    }

    (*env)->ReleaseStringUTFChars(env, code, code_utf8);

    PyGILState_Release(gstate);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeShutdown(
    JNIEnv *env, jobject obj
) {
    if (python_initialized) {
        Py_Finalize();
        python_initialized = 0;
        LOGI("nativeShutdown: Python 已关闭");
    }
}
