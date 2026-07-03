#include <jni.h>
#include <Python.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG_TAG "PythonBridge-C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Python JNI 桥接层
 */

static int python_initialized = 0;

/**
 * JNI 库加载时调用
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("JNI_OnLoad: libpython_bridge.so 已加载");
    return JNI_VERSION_1_6;
}

/**
 * 初始化 Python 运行时
 *
 * @param home  PYTHONHOME 路径（包含 lib/python3.14/ 的目录）
 * @return 0 成功，-1 失败
 */
JNIEXPORT jint JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeInit(
    JNIEnv *env, jobject obj, jstring home
) {
    if (python_initialized) {
        LOGI("nativeInit: 已初始化，跳过");
        return 0;
    }

    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    LOGI("nativeInit: PYTHONHOME=%s", home_utf8);

    PyConfig config;
    PyConfig_InitPythonConfig(&config);

    PyStatus status = PyConfig_SetBytesString(&config, &config.home, home_utf8);
    if (PyStatus_Exception(status)) {
        LOGE("nativeInit: PyConfig_SetBytesString 失败: %s", status.err_msg ? status.err_msg : "");
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        PyConfig_Clear(&config);
        return -1;
    }

    config.install_signal_handlers = 0;
    config.site_import = 0;

    LOGI("nativeInit: 调用 Py_InitializeFromConfig...");
    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    (*env)->ReleaseStringUTFChars(env, home, home_utf8);

    if (PyStatus_Exception(status)) {
        LOGE("nativeInit: Py_InitializeFromConfig 失败: %s", status.err_msg ? status.err_msg : "");
        return -1;
    }

    python_initialized = 1;
    LOGI("nativeInit: Python 初始化成功!");
    return 0;
}

/**
 * 执行 Python 代码并捕获 stdout/stderr
 *
 * @param code  要执行的 Python 代码
 * @return 执行结果（stdout + stderr 合并）
 */
JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeExec(
    JNIEnv *env, jobject obj, jstring code
) {
    if (!python_initialized) {
        // 主动尝试初始化
        LOGI("nativeExec: Python 未初始化，尝试自动初始化...");
        // 查找 PythonBridge.init 的 home 路径
        // 如果无法自动初始化，返回详细错误
        jclass cls = (*env)->FindClass(env, "com/example/agenttoolbox/tools/PythonBridge");
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "getPythonHome", "()Ljava/lang/String;");
        if (mid != NULL) {
            jstring home = (*env)->CallStaticObjectMethod(env, cls, mid);
            if (home != NULL) {
                const char *home_str = (*env)->GetStringUTFChars(env, home, NULL);
                LOGI("nativeExec: 自动初始化 PYTHONHOME=%s", home_str);
                PyConfig config;
                PyConfig_InitPythonConfig(&config);
                PyStatus status = PyConfig_SetBytesString(&config, &config.home, home_str);
                if (!PyStatus_Exception(status)) {
                    config.install_signal_handlers = 0;
                    config.site_import = 0;
                    status = Py_InitializeFromConfig(&config);
                    if (!PyStatus_Exception(status)) {
                        python_initialized = 1;
                        LOGI("nativeExec: 自动初始化成功!");
                    } else {
                        LOGE("nativeExec: 自动初始化失败: %s", status.err_msg ? status.err_msg : "");
                    }
                }
                PyConfig_Clear(&config);
                (*env)->ReleaseStringUTFChars(env, home, home_str);
            }
        }
        if (!python_initialized) {
            return (*env)->NewStringUTF(env, "错误: Python 未初始化。nativeInit 可能失败了，请检查 logcat (PythonBridge-C)");
        }
    }

    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);

    // 用 Python 的 io.StringIO 捕获输出
    const char *wrapper =
        "import sys, io\n"
        "_stdout = sys.stdout\n"
        "_stderr = sys.stderr\n"
        "sys.stdout = io.StringIO()\n"
        "sys.stderr = io.StringIO()\n"
        "_exit_code = 0\n"
        "_error_msg = ''\n"
        "try:\n"
        "    exec(compile(_code, '<agent>', 'exec'))\n"
        "except SystemExit as e:\n"
        "    _exit_code = e.code if e.code is not None else 0\n"
        "except Exception as e:\n"
        "    import traceback\n"
        "    _error_msg = traceback.format_exc()\n"
        "_out = sys.stdout.getvalue()\n"
        "_err = sys.stderr.getvalue()\n"
        "sys.stdout = _stdout\n"
        "sys.stderr = _stderr\n";

    // 设置 _code 变量
    PyObject *main_module = PyImport_AddModule("__main__");
    PyObject *globals = PyModule_GetDict(main_module);

    // 注入代码变量
    PyObject *code_obj = PyUnicode_FromString(code_utf8);
    PyDict_SetItemString(globals, "_code", code_obj);
    Py_DECREF(code_obj);

    // 执行包装脚本
    PyObject *result = PyRun_String(wrapper, Py_file_input, globals, globals);

    jstring ret;
    if (result == NULL) {
        // Python 异常
        PyObject *err = PyErr_Occurred();
        if (err) {
            PyObject *type, *value, *tb;
            PyErr_Fetch(&type, &value, &tb);
            PyErr_NormalizeException(&type, &value, &tb);

            PyObject *tb_mod = PyImport_ImportModule("traceback");
            PyObject *fmt = NULL;
            if (tb_mod && value && tb) {
                fmt = PyObject_CallMethod(tb_mod, "format_exception", "OOO", type, value, tb);
            } else if (value) {
                PyObject *str = PyObject_Str(value);
                if (str) {
                    fmt = PyList_New(1);
                    PyList_SetItem(fmt, 0, str);
                }
            }

            if (fmt) {
                PyObject *joined = PyUnicode_Join(PyUnicode_FromString(""), fmt);
                const char *err_str = PyUnicode_AsUTF8(joined);
                ret = (*env)->NewStringUTF(env, err_str ? err_str : "未知 Python 错误");
                Py_DECREF(joined);
                Py_DECREF(fmt);
            } else {
                ret = (*env)->NewStringUTF(env, "Python 执行异常（无法获取错误信息）");
            }

            Py_XDECREF(tb_mod);
            Py_XDECREF(type);
            Py_XDECREF(value);
            Py_XDECREF(tb);
            PyErr_Clear();
        } else {
            ret = (*env)->NewStringUTF(env, "Python 执行异常");
        }
    } else {
        Py_DECREF(result);

        // 读取 _out 和 _err
        PyObject *out = PyDict_GetItemString(globals, "_out");
        PyObject *err = PyDict_GetItemString(globals, "_err");
        PyObject *exit_code = PyDict_GetItemString(globals, "_exit_code");
        PyObject *error_msg = PyDict_GetItemString(globals, "_error_msg");

        const char *out_str = out ? PyUnicode_AsUTF8(out) : "";
        const char *err_str = err ? PyUnicode_AsUTF8(err) : "";
        const char *err_msg_str = (error_msg && PyUnicode_GetLength(error_msg) > 0)
                                  ? PyUnicode_AsUTF8(error_msg) : "";

        int code_val = exit_code ? (int)PyLong_AsLong(exit_code) : 0;

        // 组装结果
        char result_buf[65536];  // 64KB 上限
        int offset = 0;

        if (out_str && strlen(out_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "%s", out_str);
        }

        if (err_str && strlen(err_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "[stderr]\n%s", err_str);
        }

        if (err_msg_str && strlen(err_msg_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "[异常]\n%s", err_msg_str);
        }

        if (code_val != 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "\n[exit_code=%d]", code_val);
        }

        if (offset == 0) {
            ret = (*env)->NewStringUTF(env, "(无输出)");
        } else {
            ret = (*env)->NewStringUTF(env, result_buf);
        }
    }

    (*env)->ReleaseStringUTFChars(env, code, code_utf8);
    return ret;
}

/**
 * 关闭 Python 运行时
 */
JNIEXPORT void JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeShutdown(
    JNIEnv *env, jobject obj
) {
    if (python_initialized) {
        Py_Finalize();
        python_initialized = 0;
    }
}

/**
 * 检查 Python 是否已初始化
 */
JNIEXPORT jboolean JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeIsInitialized(
    JNIEnv *env, jobject obj
) {
    return python_initialized ? JNI_TRUE : JNI_FALSE;
}
