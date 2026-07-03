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
    private static final String EXPECTED_VERSION = "3.14.6";

    // JNI 模式
    private static boolean jniLoaded = false;
    private static boolean jniTried = false;

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
     * 初始化 Python 环境
     */
    public static synchronized boolean init(Context context) throws Exception {
        pythonHome = new File(context.getFilesDir(), PYTHON_DIR_NAME);
        File versionFile = new File(context.getFilesDir(), VERSION_FILE);

        android.util.Log.i("PythonBridge", "init: jniLoaded=" + jniLoaded + " processReady=" + processReady);

        // 已初始化
        if (jniLoaded || processReady) return true;

        // 解压标准库（两种模式都需要）
        if (!isStdlibReady(pythonHome) || !readFile(versionFile).equals(EXPECTED_VERSION)) {
            android.util.Log.i("PythonBridge", "解压标准库...");
            extractStdlib(context);
            writeFile(versionFile, EXPECTED_VERSION);
        }

        // 模式 1: JNI
        if (jniLoaded) {
            try {
                android.util.Log.i("PythonBridge", "尝试 JNI 初始化...");
                int ret = nativeInit(pythonHome.getAbsolutePath());
                android.util.Log.i("PythonBridge", "JNI 返回: " + ret);
                if (ret == 0) return true;
            } catch (Throwable e) {
                android.util.Log.e("PythonBridge", "JNI 失败: " + e.getMessage());
                jniLoaded = false;
            }
        }

        // 模式 2: 进程模式
        android.util.Log.i("PythonBridge", "尝试进程模式...");
        setupProcessMode(context);
        android.util.Log.i("PythonBridge", "进程模式: processReady=" + processReady);

        if (!processReady) {
            throw new Exception("Python 不可用：JNI 库未加载且无系统 Python。请安装 Termux 并运行 pkg install python");
        }
        return true;
    }

    /**
     * 执行 Python 代码
     */
    public static String exec(String code) throws Exception {
        // 模式 1: JNI
        if (jniLoaded) {
            try {
                return nativeExec(code);
            } catch (Exception e) {
                android.util.Log.e("PythonBridge", "JNI exec 失败: " + e.getMessage());
            }
        }

        // 模式 2: 进程模式
        if (processReady && pythonBin != null) {
            return execViaProcess(code);
        }

        throw new Exception("Python 不可用（JNI=" + jniLoaded + " process=" + processReady + "）。请先调用 init() 或安装 Termux Python");
    }

    public static boolean isAvailable() {
        return jniLoaded || processReady;
    }

    public static boolean isInitialized() {
        return (jniLoaded && jniTried) || processReady;
    }

    public static String getStatus() {
        if (jniLoaded) return "JNI 模式";
        if (processReady) return "进程模式 (wrapper)";
        return "未初始化";
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
        if (pythonHome.exists()) deleteRecursive(pythonHome);
        pythonHome.mkdirs();
        extractAssetDir(context, STDLIB_ASSET_DIR, pythonHome);
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
        File subDir = new File(targetDir, assetPath.substring(assetPath.lastIndexOf('/') + 1));
        if (!subDir.exists()) subDir.mkdirs();
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);
            if (childNames != null && childNames.length > 0) {
                File childDir = new File(subDir, name);
                if (!childDir.exists()) childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            } else {
                extractAssetFile(context, childPath, subDir);
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
        return dir.exists() && new File(dir, "os.py").exists();
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
