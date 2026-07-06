package com.example.agenttoolbox.tools;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Python 桥接层 - JNI 内嵌模式
 *
 * 使用 Python 3.14.6 官方 Android (aarch64) 构建，通过 JNI 嵌入执行。
 *
 * 目录结构:
 *   assets/python/stdlib/   → 提取到 pythonHome/lib/python3.14/
 *   PYTHONHOME = pythonHome
 *
 * Python 在 PYTHONHOME/lib/python3.14/ 下查找 os.py、encodings/ 等标准库。
 */
public class PythonBridge {

    // assets 中标准库目录（官方包 prefix/lib/python3.14/ 的内容）
    private static final String STDLIB_ASSET_DIR = "python/stdlib";
    private static final String PYTHON_DIR_NAME = "python";
    private static final String VERSION_FILE = ".python_version";
    private static final String EXPECTED_VERSION = "3.14.6-official-v1";

    private static boolean jniLoaded = false;
    private static boolean jniInitOk = false;
    private static int jniInitRetCode = 0;
    private static String jniInitError = "";
    private static File pythonHome;

    static {
        try {
            System.loadLibrary("python_bridge");
            jniLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            jniLoaded = false;
        }
    }

    public static synchronized boolean init(Context context) throws Exception {
        pythonHome = new File(context.getFilesDir(), PYTHON_DIR_NAME);
        File versionFile = new File(context.getFilesDir(), VERSION_FILE);

        android.util.Log.i("PythonBridge", "init: jniLoaded=" + jniLoaded + " jniInitOk=" + jniInitOk);

        if (jniInitOk) return true;

        // 解压标准库（版本不匹配时重新解压）
        if (!isStdlibReady(pythonHome) || !readFile(versionFile).equals(EXPECTED_VERSION)) {
            android.util.Log.i("PythonBridge", "解压标准库到 " + pythonHome.getAbsolutePath() + " ...");
            extractStdlib(context);
            writeFile(versionFile, EXPECTED_VERSION);
            android.util.Log.i("PythonBridge",
                "标准库解压完成，os.py=" + new File(pythonHome, "lib/python3.14/os.py").exists());
        }

        if (!jniLoaded) {
            jniInitError = "libpython_bridge.so 未加载";
            throw new Exception("JNI 库未加载: " + jniInitError);
        }

        try {
            String homePath = pythonHome.getAbsolutePath();
            android.util.Log.i("PythonBridge", "JNI init: PYTHONHOME=" + homePath);
            android.util.Log.i("PythonBridge", "JNI init: os.py=" + new File(pythonHome, "lib/python3.14/os.py").exists());
            android.util.Log.i("PythonBridge", "JNI init: encodings=" + new File(pythonHome, "lib/python3.14/encodings/__init__.py").exists());

            jniInitRetCode = nativeInit(homePath);
            android.util.Log.i("PythonBridge", "JNI init 返回码: " + jniInitRetCode);

            if (jniInitRetCode == 0) {
                jniInitOk = true;
                jniInitError = "";
                android.util.Log.i("PythonBridge", "JNI 初始化成功!");
                return true;
            }

            jniInitError = nativeGetLastError();
            android.util.Log.e("PythonBridge", "JNI init 失败: ret=" + jniInitRetCode + " error=" + jniInitError);
            throw new Exception("JNI 初始化失败 (ret=" + jniInitRetCode + "): " + jniInitError);

        } catch (Throwable e) {
            jniInitError = e.getMessage();
            android.util.Log.e("PythonBridge", "JNI init 异常: " + jniInitError);
            throw new Exception("JNI 初始化异常: " + jniInitError);
        }
    }

    public static String exec(String code) throws Exception {
        if (!jniLoaded) {
            return "[错误] JNI 库未加载 (libpython_bridge.so)";
        }
        if (!jniInitOk) {
            return "[错误] Python 未初始化 (ret=" + jniInitRetCode + "): " + jniInitError
                + "\n  请重启应用或查看 logcat (PythonBridge-C)";
        }
        try {
            return nativeExec(code);
        } catch (Exception e) {
            return "[错误] JNI 执行异常: " + e.getMessage();
        }
    }

    public static boolean isAvailable() { return jniLoaded && jniInitOk; }
    public static boolean isInitialized() { return jniInitOk; }

    public static String getStatus() {
        if (jniInitOk) return "JNI 内嵌模式 - 就绪";
        if (!jniLoaded) return "JNI 内嵌模式 - 库未加载";
        return "JNI 内嵌模式 - 未初始化 (ret=" + jniInitRetCode + "): " + jniInitError;
    }

    public static void shutdown() {
        if (jniLoaded) {
            try { nativeShutdown(); } catch (Exception ignored) {}
        }
    }

    // ===== JNI =====
    private static native int nativeInit(String home);
    private static native String nativeExec(String code);
    private static native void nativeShutdown();
    private static native boolean nativeIsInitialized();
    private static native String nativeGetLastError();

    /** 供 native 层回调获取 Python Home 路径 */
    private static String getPythonHome() {
        return pythonHome != null ? pythonHome.getAbsolutePath() : null;
    }

    // ===== 标准库提取 =====

    private static void extractStdlib(Context context) throws Exception {
        // assets/python/stdlib/ → pythonHome/lib/python3.14/
        File libDir = new File(pythonHome, "lib/python3.14");
        if (pythonHome.exists()) deleteRecursive(pythonHome);
        libDir.mkdirs();

        android.util.Log.i("PythonBridge", "提取 " + STDLIB_ASSET_DIR + " → " + libDir.getAbsolutePath());
        extractAssetDir(context, STDLIB_ASSET_DIR, libDir);

        // 设置 lib-dynload 中 .so 文件的可执行权限
        File dynloadDir = new File(libDir, "lib-dynload");
        if (dynloadDir.exists()) {
            File[] sos = dynloadDir.listFiles();
            if (sos != null) {
                for (File so : sos) {
                    so.setExecutable(true, false);
                }
                android.util.Log.i("PythonBridge", "lib-dynload: " + sos.length + " 个 .so 已设置可执行权限");
            }
        }

        // 诊断日志
        android.util.Log.i("PythonBridge", "=== 提取后文件树 ===");
        logFileTree(pythonHome, 0);
    }

    private static boolean isStdlibReady(File dir) {
        return dir.exists()
            && new File(dir, "lib/python3.14/os.py").exists()
            && new File(dir, "lib/python3.14/encodings/__init__.py").exists();
    }

    private static void logFileTree(File dir, int depth) {
        if (depth > 3) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        int limit = Math.min(children.length, 20);
        for (int i = 0; i < limit; i++) {
            File f = children[i];
            String indent = "  ";
            for (int j = 0; j < depth; j++) indent += "  ";
            if (f.isDirectory()) {
                android.util.Log.i("PythonBridge", indent + f.getName() + "/");
                logFileTree(f, depth + 1);
            } else {
                android.util.Log.i("PythonBridge", indent + f.getName() + " (" + f.length() + "B)");
            }
        }
        if (children.length > 20) {
            android.util.Log.i("PythonBridge", "  ... 还有 " + (children.length - 20) + " 项");
        }
    }

    private static void extractAssetDir(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names = context.getAssets().list(assetPath);
        if (names == null || names.length == 0) {
            extractAssetFile(context, assetPath, targetDir);
            return;
        }
        if (!targetDir.exists()) targetDir.mkdirs();
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);
            if (childNames != null && childNames.length > 0) {
                File childDir = new File(targetDir, name);
                childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            } else {
                extractAssetFile(context, childPath, targetDir);
            }
        }
    }

    private static void extractAssetDirRecursive(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names = context.getAssets().list(assetPath);
        if (names == null) return;
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);
            if (childNames != null && childNames.length > 0) {
                File childDir = new File(targetDir, name);
                childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            } else {
                extractAssetFile(context, childPath, targetDir);
            }
        }
    }

    private static void extractAssetFile(Context context, String assetPath, File targetDir)
            throws Exception {
        String fileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File outFile = new File(targetDir, fileName);
        InputStream is = context.getAssets().open(assetPath);
        try {
            FileOutputStream fos = new FileOutputStream(outFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.flush();
            } finally { fos.close(); }
        } finally { is.close(); }
        // .so 文件设置可执行权限
        if (fileName.endsWith(".so")) outFile.setExecutable(true, false);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        f.delete();
    }

    private static String readFile(File f) {
        try {
            byte[] d = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(d); fis.close();
            return new String(d, "UTF-8").trim();
        } catch (Exception e) { return ""; }
    }

    private static void writeFile(File f, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(s.getBytes("UTF-8")); fos.close();
        } catch (Exception ignored) {}
    }
}
