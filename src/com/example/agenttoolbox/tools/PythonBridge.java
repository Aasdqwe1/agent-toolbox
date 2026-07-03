package com.example.agenttoolbox.tools;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Python 桥接层 - 双模式
 *
 * 模式 1: JNI 编译模式（需要 NDK）
 *   - System.loadLibrary("python_bridge")
 *   - 调用 native 方法执行 Python
 *
 * 模式 2: 进程模式（不需要 NDK，降级方案）
 *   - 从 jniLibs 提取 libpython3.14.so
 *   - 写一个最小的 python3 可执行脚本
 *   - 通过 Runtime.exec() 调用
 */
public class PythonBridge {

    private static final String STDLIB_ASSET_DIR = "python/stdlib";
    private static final String PYTHON_DIR_NAME = "python";
    private static final String VERSION_FILE = ".python_version";
    private static final String EXPECTED_VERSION = "3.14.6-v4";

    // JNI 模式
    private static boolean jniLoaded = false;
    private static boolean jniTried = false;
    private static boolean jniInitOk = false;
    private static int jniInitRetCode = 0;
    private static String jniInitError = "";

    // 进程模式
    private static File pythonHome;
    private static File pythonBin;  // 包装脚本
    private static boolean processReady = false;

    static {
        try {
            System.loadLibrary("python_bridge");
            jniLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            jniLoaded = false;
        }
        jniTried = true;
    }

    /**
     * 初始化 Python 环境（仅 JNI 内嵌模式）
     */
    public static synchronized boolean init(Context context) throws Exception {
        pythonHome = new File(context.getFilesDir(), PYTHON_DIR_NAME);
        File versionFile = new File(context.getFilesDir(), VERSION_FILE);

        android.util.Log.i("PythonBridge", "init: jniLoaded=" + jniLoaded + " jniInitOk=" + jniInitOk);

        // 已初始化
        if (jniInitOk) return true;

        // 解压标准库
        if (!isStdlibReady(pythonHome) || !readFile(versionFile).equals(EXPECTED_VERSION)) {
            android.util.Log.i("PythonBridge", "解压标准库到 " + pythonHome.getAbsolutePath() + " ...");
            extractStdlib(context);
            writeFile(versionFile, EXPECTED_VERSION);
            android.util.Log.i("PythonBridge", "标准库解压完成，os.py=" + new File(pythonHome, "os.py").exists());
        }

        // JNI 内嵌模式
        if (!jniLoaded) {
            jniInitError = "libpython_bridge.so 未加载，请检查 jniLibs 目录";
            throw new Exception("JNI 库未加载: " + jniInitError);
        }

        try {
            String homePath = pythonHome.getAbsolutePath();
            android.util.Log.i("PythonBridge", "JNI init: PYTHONHOME=" + homePath);
            android.util.Log.i("PythonBridge", "JNI init: pythonHome 存在=" + pythonHome.exists() + " 路径=" + homePath);
            android.util.Log.i("PythonBridge", "JNI init: lib/python3.14/os.py=" + new File(pythonHome, "lib/python3.14/os.py").exists());

            // 先用 nativeTestInit 安全检测（带信号保护，不会 crash 进程）
            android.util.Log.i("PythonBridge", "JNI init: 调用 nativeTestInit 安全检测...");
            jniInitRetCode = nativeTestInit(homePath);
            android.util.Log.i("PythonBridge", "JNI nativeTestInit 返回码: " + jniInitRetCode);

            if (jniInitRetCode == 0) {
                jniInitOk = true;
                jniInitError = "";
                android.util.Log.i("PythonBridge", "JNI 初始化成功! (via nativeTestInit)");
                return true;
            }

            // nativeTestInit 失败，获取错误
            jniInitError = nativeGetLastError();
            android.util.Log.e("PythonBridge", "JNI init 失败: ret=" + jniInitRetCode + " error=" + jniInitError);
            throw new Exception("JNI 初始化失败 (ret=" + jniInitRetCode + "): " + jniInitError);

        } catch (Throwable e) {
            jniInitError = e.getMessage();
            android.util.Log.e("PythonBridge", "JNI init 异常: " + jniInitError);
            throw new Exception("JNI 初始化异常: " + jniInitError);
        }
    }

    /**
     * 执行 Python 代码（仅 JNI 内嵌模式）
     */
    public static String exec(String code) throws Exception {
        if (!jniLoaded) {
            return "[错误] JNI 库未加载\n"
                + "  - libpython_bridge.so 未找到\n"
                + "  - 请检查 app/src/main/jniLibs/arm64-v8a/ 目录";
        }

        if (!jniInitOk) {
            String homePath = (pythonHome != null) ? pythonHome.getAbsolutePath() : "未设置";
            return "[错误] Python 未初始化，详细信息:\n"
                + "  - JNI 库: 已加载 (libpython_bridge.so)\n"
                + "  - JNI init: 失败 (返回码=" + jniInitRetCode + ")\n"
                + "  - 错误信息: " + (jniInitError.isEmpty() ? "无" : jniInitError) + "\n"
                + "  - PYTHONHOME: " + homePath + "\n"
                + "  - 请重启应用或查看 logcat 标签 PythonBridge-C 排查原因";
        }

        try {
            return nativeExec(code);
        } catch (Exception e) {
            android.util.Log.e("PythonBridge", "JNI exec 异常: " + e.getMessage());
            return "[错误] JNI 执行异常: " + e.getMessage();
        }
    }

    public static boolean isAvailable() {
        return jniLoaded && jniInitOk;
    }

    public static boolean isInitialized() {
        return jniInitOk;
    }

    public static String getStatus() {
        if (jniInitOk) return "JNI 内嵌模式 - 就绪";
        if (!jniLoaded) return "JNI 内嵌模式 - 库未加载 (libpython_bridge.so)";
        return "JNI 内嵌模式 - 未初始化 (ret=" + jniInitRetCode + "): " + jniInitError;
    }

    public static void shutdown() {
        if (jniLoaded) {
            try { nativeShutdown(); } catch (Exception ignored) {}
        }
    }

    // ===== JNI =====
    private static native int nativeInit(String home);
    private static native int nativeTestInit(String home);
    private static native String nativeExec(String code);
    private static native void nativeShutdown();
    private static native boolean nativeIsInitialized();
    private static native String nativeGetLastError();

    /**
     * 供 native 层回调获取 Python Home 路径
     */
    private static String getPythonHome() {
        return pythonHome != null ? pythonHome.getAbsolutePath() : null;
    }

    // ===== 进程模式 =====

    /**
     * 设置进程模式：创建一个 python3 包装脚本
     *
     * 利用 LD_LIBRARY_PATH 指向 libpython3.14.so 所在目录，
     * 然后通过 linker 直接加载 Python 共享库来执行代码。
     *
     * 但更实际的做法是：找到系统上任何可用的 python 可执行文件，
     * 设置好 PYTHONHOME 让它用我们的标准库。
     */
    private static void setupProcessMode(Context context) {
        // 尝试查找系统上已有的 Python
        String[] candidates = {
            "python3", "python",
            "/data/data/com.termux/files/usr/bin/python3",
            "/system/bin/python3",
        };

        for (String cmd : candidates) {
            try {
                String[] testCmd = {"sh", "-c", "which " + cmd + " 2>/dev/null"};
                Process p = Runtime.getRuntime().exec(testCmd);
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String path = br.readLine();
                p.waitFor();
                br.close();

                if (path != null && !path.trim().isEmpty()) {
                    // 找到了 Python！创建包装脚本设置 PYTHONHOME
                    pythonBin = createWrapperScript(context, path.trim());
                    processReady = true;
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // 没找到系统 Python，尝试用 libpython3.14.so 的方式
        // 创建一个极简的 C 程序来调用 Py_Main
        // 这需要 NDK 编译... 如果没有 NDK 就没办法了
        processReady = false;
    }

    /**
     * 创建包装脚本：设置 PYTHONHOME 后调用系统 Python
     */
    private static File createWrapperScript(Context context, String pythonPath) {
        File script = new File(context.getFilesDir(), "python_wrapper.sh");
        try {
            String content = "#!/system/bin/sh\n"
                + "export PYTHONHOME=" + pythonHome.getAbsolutePath() + "\n"
                + "export PYTHONPATH=" + pythonHome.getAbsolutePath() + "/lib/python3.14\n"
                + "export TMPDIR=" + context.getCacheDir().getAbsolutePath() + "\n"
                + "exec " + pythonPath + " \"$@\"\n";
            FileOutputStream fos = new FileOutputStream(script);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            script.setExecutable(true, false);
        } catch (Exception ignored) {
        }
        return script;
    }

    /**
     * 通过进程执行 Python 代码
     */
    private static String execViaProcess(String code) throws Exception {
        // 用 python3 -c 执行
        String escaped = code.replace("'", "'\\''");
        String[] cmd = {"sh", pythonBin.getAbsolutePath(), "-c", escaped};

        final Process process = Runtime.getRuntime().exec(cmd);
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();

        Thread t1 = new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                    br.close();
                } catch (Exception ignored) {}
            }
        });

        Thread t2 = new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getErrorStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                    br.close();
                } catch (Exception ignored) {}
            }
        });

        t1.start();
        t2.start();
        process.waitFor();
        t1.join(5000);
        t2.join(5000);

        StringBuilder result = new StringBuilder();
        if (stdout.length() > 0) result.append(stdout);
        if (stderr.length() > 0) result.append("[stderr]\n").append(stderr);
        return result.toString();
    }

    // ===== 内部工具 =====

    private static void extractStdlib(Context context) throws Exception {
        // assets 中 python/stdlib/ 下是标准库文件（含 encodings/ 等子目录）
        // Python 需要 PYTHONHOME/lib/python3.14/ 下找到它们
        // 所以提取到 pythonHome/lib/python3.14/
        File libDir = new File(pythonHome, "lib/python3.14");
        if (pythonHome.exists()) deleteRecursive(pythonHome);
        libDir.mkdirs();

        android.util.Log.i("PythonBridge", "解压标准库到 " + libDir.getAbsolutePath() + " ...");
        extractAssetDir(context, STDLIB_ASSET_DIR, libDir);

        // 诊断
        android.util.Log.i("PythonBridge", "=== 提取后文件树: " + pythonHome.getAbsolutePath() + " ===");
        logFileTree(pythonHome, 0);
    }

    /**
     * 递归列出 assets 目录结构（日志）
     */
    private static void logAssetTree(Context context, String assetPath, int depth) {
        String[] names;
        try {
            names = context.getAssets().list(assetPath);
        } catch (Exception e) {
            android.util.Log.i("PythonBridge", indent(depth) + "[无法列出: " + e.getMessage() + "]");
            return;
        }
        if (names == null || names.length == 0) {
            android.util.Log.i("PythonBridge", indent(depth) + "(文件) " + assetPath);
            return;
        }
        android.util.Log.i("PythonBridge", indent(depth) + assetPath + "/ [" + names.length + "项]");
        // 最多输出前30项避免日志爆炸
        int limit = Math.min(names.length, 30);
        for (int i = 0; i < limit; i++) {
            logAssetTree(context, assetPath + "/" + names[i], depth + 1);
        }
        if (names.length > 30) {
            android.util.Log.i("PythonBridge", indent(depth + 1) + "... 还有 " + (names.length - 30) + " 项");
        }
    }

    /**
     * 递归列出文件目录树（日志）
     */
    private static void logFileTree(File dir, int depth) {
        if (depth > 5) {
            android.util.Log.i("PythonBridge", indent(depth) + "(深度限制，停止)");
            return;
        }
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            android.util.Log.i("PythonBridge", indent(depth) + "(空目录) " + dir.getName());
            return;
        }
        // 最多输出前30项
        int limit = Math.min(children.length, 30);
        for (int i = 0; i < limit; i++) {
            File f = children[i];
            if (f.isDirectory()) {
                android.util.Log.i("PythonBridge", indent(depth) + f.getName() + "/");
                logFileTree(f, depth + 1);
            } else {
                android.util.Log.i("PythonBridge", indent(depth) + f.getName() + " (" + f.length() + "B)");
            }
        }
        if (children.length > 30) {
            android.util.Log.i("PythonBridge", indent(depth) + "... 还有 " + (children.length - 30) + " 项");
        }
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private static void extractAssetDir(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names;
        try {
            names = context.getAssets().list(assetPath);
        } catch (Exception e) {
            return;
        }
        if (names == null || names.length == 0) {
            extractAssetFile(context, assetPath, targetDir);
            return;
        }
        // 直接提取到 targetDir，不额外包装子目录
        if (!targetDir.exists()) targetDir.mkdirs();
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);
            if (childNames != null && childNames.length > 0) {
                File childDir = new File(targetDir, name);
                if (!childDir.exists()) childDir.mkdirs();
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
                if (!childDir.exists()) childDir.mkdirs();
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
    }

    private static boolean isStdlibReady(File dir) {
        // assets 中 python/stdlib/lib/python3.14/... 直接提取到 dir/lib/python3.14/...
        return dir.exists()
            && new File(dir, "lib/python3.14/os.py").exists();
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
