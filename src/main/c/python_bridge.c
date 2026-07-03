#include <jni.h>
#include <Python.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>
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

// Android 应用进程 fd 0/1/2 可能关闭，导致 Python 初始化 stdio 崩溃
// 重新打开到 /dev/null 作为安全兜底
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

    // 尝试预加载 libpython3.14.so
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

    // 先尝试加载 libpython3.14.so
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

    // 设置环境变量
    setenv("PYTHONHOME", home_utf8, 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);

    // 预初始化（isolated 模式，不读系统配置）
    LOGI("nativeInit: Py_PreInitialize (isolated)...");
    PyPreConfig preconfig;
    PyPreConfig_InitIsolatedConfig(&preconfig);
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

    // 用 PyConfig_InitPythonConfig 初始化（让 Python 检测 Android 平台）
    LOGI("nativeInit: PyConfig_InitPythonConfig...");
    PyConfig config;
    PyConfig_InitPythonConfig(&config);

    status = PyConfig_SetBytesString(&config, &config.home, home_utf8);
    if (PyStatus_Exception(status)) {
        snprintf(last_error, sizeof(last_error), "PyConfig_SetBytesString(home) 失败: %s",
                 status.err_msg ? status.err_msg : "unknown");
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        PyConfig_Clear(&config);
        return -2;
    }

    // 关键 Android 适配参数
    config.install_signal_handlers = 0;   // 禁用信号处理
    config.site_import = 0;                // 禁用 site
    config.parse_argv = 0;                // 不解析 argv
    config.buffered_stdio = 0;            // 禁用 buffered stdio（避免 Android streams 问题）
    config.interactive = 0;
    config.optimization_level = 2;
    config.quiet = 1;

    // 设置 executable 绕过 _android_support.init_streams()
    // init_streams() 检查 sys.executable 非空则跳过 Android 流初始化
    status = PyConfig_SetBytesString(&config, &config.executable,
                                      "/data/user/0/com.example.agenttoolbox/files/python/python3");
    if (PyStatus_Exception(status)) {
        LOGI("nativeInit: PyConfig_SetBytesString(executable) 失败(非致命): %s",
             status.err_msg ? status.err_msg : "unknown");
    }

    LOGI("nativeInit: PyConfig: home=%s buffered_stdio=0 parse_argv=0 install_signal_handlers=0",
         home_utf8);

    LOGI("nativeInit: Py_InitializeFromConfig...");
    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    (*env)->ReleaseStringUTFChars(env, home, home_utf8);

    if (PyStatus_Exception(status)) {
        const char *err_msg = status.err_msg ? status.err_msg : "unknown";
        // 尝试捕获 Python 异常详情
        PyObject *exc_type = NULL, *exc_val = NULL, *exc_tb = NULL;
        PyErr_Fetch(&exc_type, &exc_val, &exc_tb);
        char detail[1024] = "";
        if (exc_val) {
            PyObject *str = PyObject_Str(exc_val);
            if (str) {
                const char *s = PyUnicode_AsUTF8(str);
                if (s) snprintf(detail, sizeof(detail), " | 异常: %s", s);
                Py_DECREF(str);
            }
            Py_XDECREF(exc_type); Py_XDECREF(exc_val); Py_XDECREF(exc_tb);
            PyErr_Clear();
        }
        snprintf(last_error, sizeof(last_error), "Py_InitializeFromConfig 失败: %s%s",
                 err_msg, detail);
        LOGE("nativeInit: %s", last_error);
        return -3;
    }

    LOGI("nativeInit: Py_InitializeFromConfig 成功!");

    // 确保 GIL 已获取
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

    // 捕获 stdout + stderr
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
