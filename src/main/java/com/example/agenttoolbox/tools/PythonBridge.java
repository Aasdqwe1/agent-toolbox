package com.example.agenttoolbox.tools;

import android.content.Context;

import com.example.agenttoolbox.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    // v6: pip 改为 zip 打包（绕过 AAPT2 深层嵌套目录丢失 bug），运行时解压
    private static final String EXPECTED_VERSION = "3.14.6-official-v6";

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

        AppLogger.i("PythonBridge", "init: jniLoaded=" + jniLoaded + " jniInitOk=" + jniInitOk);

        if (jniInitOk) return true;

        // 解压标准库（版本不匹配时重新解压）
        if (!isStdlibReady(pythonHome) || !readFile(versionFile).equals(EXPECTED_VERSION)) {
            AppLogger.i("PythonBridge", "解压标准库到 " + pythonHome.getAbsolutePath() + " ...");
            extractStdlib(context);
            writeFile(versionFile, EXPECTED_VERSION);
            AppLogger.i("PythonBridge",
                "标准库解压完成，os.py=" + new File(pythonHome, "lib/python3.14/os.py").exists());
        }

        if (!jniLoaded) {
            jniInitError = "libpython_bridge.so 未加载";
            throw new Exception("JNI 库未加载: " + jniInitError);
        }

        try {
            String homePath = pythonHome.getAbsolutePath();
            AppLogger.i("PythonBridge", "JNI init: PYTHONHOME=" + homePath);
            AppLogger.i("PythonBridge", "JNI init: os.py=" + new File(pythonHome, "lib/python3.14/os.py").exists());
            AppLogger.i("PythonBridge", "JNI init: encodings=" + new File(pythonHome, "lib/python3.14/encodings/__init__.py").exists());

            jniInitRetCode = nativeInit(homePath);
            AppLogger.i("PythonBridge", "JNI init 返回码: " + jniInitRetCode);

            if (jniInitRetCode == 0) {
                jniInitOk = true;
                jniInitError = "";
                AppLogger.i("PythonBridge", "JNI 初始化成功!");
                // pip 已直接打包进 stdlib（assets/python/stdlib/pip/），
                // 随 stdlib 一起提取到 pythonHome/lib/python3.14/，
                // 处于默认 sys.path 中，无需运行时引导安装。
                // 提取内嵌 git 二进制并注入 PATH，让 python 的 subprocess 能找到 git
                ensureGitBinaryOnPath(context);
                return true;
            }

            jniInitError = nativeGetLastError();
            AppLogger.e("PythonBridge", "JNI init 失败: ret=" + jniInitRetCode + " error=" + jniInitError);
            throw new Exception("JNI 初始化失败 (ret=" + jniInitRetCode + "): " + jniInitError);

        } catch (Throwable e) {
            jniInitError = e.getMessage();
            AppLogger.e("PythonBridge", "JNI init 异常: " + jniInitError);
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

    /**
     * 确保内嵌 git 二进制可用并注入 PATH，让 python 的 subprocess 调用 git 时能找到。
     * 优先使用 nativeLibraryDir/libgit.so（SELinux 标记为 app_lib_data_file，允许执行）。
     * 回退到从 assets/git/git 提取到 filesDir/git_bin（可能因 SELinux 不可执行）。
     * 失败时静默跳过（dulwich 兜底仍可用）。
     */
    private static void ensureGitBinaryOnPath(Context context) {
        // 优先：nativeLibraryDir/libgit.so（APK 安装时自动解压，SELinux 允许执行）
        File libGit = null;
        String nativeLibDir = null;
        try {
            nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            libGit = new File(nativeLibDir, "libgit.so");
            if (!libGit.exists() || !libGit.canExecute()) {
                libGit = null;
            }
        } catch (Exception ignored) {}

        // 回退：从 assets 提取到 filesDir/git_bin
        if (libGit == null) {
            libGit = extractGitToFilesDir(context);
        }
        if (libGit == null) {
            AppLogger.w("PythonBridge", "无可用 git 二进制，跳过 PATH 注入（dulwich 兜底）");
            return;
        }

        // 创建 GIT_EXEC_PATH 目录（含 git-remote-https 符号链接）
        String gitExecPath = ensureGitExecDir(context, nativeLibDir);

        // 注入 PATH + GIT_EXEC_PATH + HOME 到 Python 环境
        File gitDir = libGit.getParentFile();
        File gitLink = new File(gitDir, "git");
        if (!gitLink.exists()) {
            try {
                java.nio.file.Files.copy(libGit.toPath(), gitLink.toPath());
                gitLink.setExecutable(true, false);
            } catch (Exception e) {
                AppLogger.w("PythonBridge", "无法创建 git 副本: " + e.getMessage());
            }
        }
        String pathDir = gitDir.getAbsolutePath();
        String filesDir = context.getFilesDir().getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        sb.append("import os\n");
        // PATH 注入
        sb.append("_d = ").append(repr(pathDir)).append("\n");
        sb.append("_p = os.environ.get('PATH', '')\n");
        sb.append("if _d not in _p.split(os.pathsep):\n");
        sb.append("    os.environ['PATH'] = _d + os.pathsep + _p\n");
        // GIT_EXEC_PATH 注入（让 git 找到 git-remote-https helper）
        if (gitExecPath != null) {
            sb.append("os.environ['GIT_EXEC_PATH'] = ").append(repr(gitExecPath)).append("\n");
        }
        // HOME 注入（git 需要）
        sb.append("os.environ['HOME'] = ").append(repr(filesDir)).append("\n");
        // GIT_TEMPLATE_DIR 避免 templates not found 警告
        sb.append("os.environ['GIT_TEMPLATE_DIR'] = ''\n");
        // c-ares DNS 服务器（Android 静态二进制的 getaddrinfo 不工作）
        sb.append("os.environ['GIT_DNS_SERVERS'] = '8.8.8.8,8.8.4.4,1.1.1.1'\n");
        // SSL: 用 GIT_SSL_NO_VERIFY=true 跳过验证（静态 OpenSSL 加载 CA 文件段错误）
        // 删除旧 .gitconfig 防止 v2.4.14 的 sslCAInfo 残留导致段错误
        cleanupGitConfig(context);
        sb.append("os.environ['GIT_SSL_NO_VERIFY'] = 'true'\n");
        // SSL_CERT_FILE + CURL_CA_BUNDLE: 指向内嵌 Mozilla CA 证书包，
        // 防止静态 OpenSSL 走编译时默认路径(/tmp/static_prefix/ssl/certs/)导致段错误
        String caBundle = ensureCacertBundle(context);
        if (caBundle != null) {
            sb.append("os.environ['SSL_CERT_FILE'] = ").append(repr(caBundle)).append("\n");
            sb.append("os.environ['CURL_CA_BUNDLE'] = ").append(repr(caBundle)).append("\n");
        }
        // 如果 'git' 名不存在但 libgit.so 存在，patch subprocess
        sb.append("if not os.path.exists(os.path.join(_d, 'git')):\n");
        sb.append("    import subprocess as _sp\n");
        sb.append("    _orig_popen = _sp.Popen\n");
        sb.append("    class _GitPopen(_orig_popen):\n");
        sb.append("        def __init__(self, args, *a, **kw):\n");
        sb.append("            if isinstance(args, list) and args and args[0] == 'git':\n");
        sb.append("                args = ['libgit.so'] + args[1:]\n");
        sb.append("            _orig_popen.__init__(self, args, *a, **kw)\n");
        sb.append("    _sp.Popen = _GitPopen\n");
        try {
            nativeExec(sb.toString());
            AppLogger.i("PythonBridge", "已注入 git 路径 + GIT_EXEC_PATH 到 Python 环境");
        } catch (Exception e) {
            AppLogger.w("PythonBridge", "PATH 注入失败: " + e.getMessage());
        }
    }

    /**
     * 在 filesDir/git-exec/ 创建符号链接：
     * - git-remote-https → nativeLibraryDir/libgitremotehttps.so
     * - git → nativeLibraryDir/libgit.so
     * execve 符号链接时 SELinux 检查目标文件（app_lib_data_file 允许执行）。
     * @return GIT_EXEC_PATH 路径，或 null
     */
    private static String ensureGitExecDir(Context context, String nativeLibDir) {
        if (context == null || nativeLibDir == null) return null;
        try {
            File execDir = new File(context.getFilesDir(), "git-exec");
            if (!execDir.exists()) execDir.mkdirs();
            createGitSymlink(new File(nativeLibDir, "libgitremotehttps.so"),
                            new File(execDir, "git-remote-https"));
            createGitSymlink(new File(nativeLibDir, "libgit.so"),
                            new File(execDir, "git"));
            return execDir.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void createGitSymlink(File target, File link) {
        try {
            // 用 readlink 检测（对断链也有效），不用 File.exists()（对断链返回 false）
            try {
                String lt = android.system.Os.readlink(link.getAbsolutePath());
                if (lt.equals(target.getAbsolutePath())) return;
            } catch (Exception ignored) {}
            link.delete();
            try { android.system.Os.remove(link.getAbsolutePath()); } catch (Exception ignored) {}
            android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception ignored) {}
    }

    /**
     * 从 assets/git/cacert.pem 提取 Mozilla CA 证书包到 filesDir/cacert.pem。
     * 静态 OpenSSL 无内置 CA 路径，需要显式提供 CA 证书用于 HTTPS 验证。
     * @return cacert.pem 路径，或 null
     */
    private static String ensureCacertBundle(Context context) {
        if (context == null) return null;
        try {
            File caFile = new File(context.getFilesDir(), "cacert.pem");
            if (caFile.exists() && caFile.length() > 1024) {
                return caFile.getAbsolutePath();
            }
            InputStream is = context.getAssets().open("git/cacert.pem");
            try {
                FileOutputStream fos = new FileOutputStream(caFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally { fos.close(); }
            } finally { is.close(); }
            if (caFile.length() > 1024) {
                return caFile.getAbsolutePath();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除 .gitconfig（清除 v2.4.14 的 sslCAInfo，导致 git-remote-https 段错误）。
     * SSL 验证改用 GIT_SSL_NO_VERIFY=true 环境变量。
     */
    private static void cleanupGitConfig(Context context) {
        if (context == null) return;
        try {
            File gitconfig = new File(context.getFilesDir(), ".gitconfig");
            if (gitconfig.exists()) {
                gitconfig.delete();
            }
        } catch (Exception ignored) {}
    }

    /** 从 assets/git/git 提取到 filesDir/git_bin，返回可执行文件或 null */
    private static File extractGitToFilesDir(Context context) {
        try {
            File outFile = new File(context.getFilesDir(), "git_bin");
            // 检查 assets 中是否有 git 二进制
            try {
                context.getAssets().open("git/git").close();
            } catch (IOException e) {
                return null;
            }
            if (outFile.exists() && outFile.canExecute()) {
                return outFile;
            }
            InputStream is = context.getAssets().open("git/git");
            try {
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally { fos.close(); }
            } finally { is.close(); }
            outFile.setExecutable(true, false);
            try {
                new ProcessBuilder("chmod", "755", outFile.getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {}
            if (!outFile.canExecute()) return null;
            return outFile;
        } catch (Exception e) {
            return null;
        }
    }

    /** Java 字符串转 Python 字面量（单引号包围，转义内部单引号和反斜杠） */
    private static String repr(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') sb.append('\\');
            sb.append(c);
        }
        sb.append("'");
        return sb.toString();
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

        AppLogger.i("PythonBridge", "提取 " + STDLIB_ASSET_DIR + " → " + libDir.getAbsolutePath());
        extractAssetDir(context, STDLIB_ASSET_DIR, libDir);

        // pip 因目录嵌套过深（pip/_internal/ 148 文件 + pip/_vendor/ 数百文件），
        // AAPT2 打包时会丢失这些深层子目录，导致运行时 import pip._internal 报
        // ModuleNotFoundError。改为把 pip 整体打包成 pip.zip 作为单个 asset，
        // AAPT2 不会跳过单文件，运行时在这里解压。
        extractPipZip(context, libDir);

        // 设置 lib-dynload 中 .so 文件的可执行权限
        File dynloadDir = new File(libDir, "lib-dynload");
        if (dynloadDir.exists()) {
            File[] sos = dynloadDir.listFiles();
            if (sos != null) {
                for (File so : sos) {
                    so.setExecutable(true, false);
                }
                AppLogger.i("PythonBridge", "lib-dynload: " + sos.length + " 个 .so 已设置可执行权限");
            }
        }

        // 诊断日志
        AppLogger.i("PythonBridge", "=== 提取后文件树 ===");
        logFileTree(pythonHome, 0);
    }

    /**
     * 从 assets/python/stdlib/pip.zip 解压 pip 到 libDir/pip/
     * zip 内部结构为 pip/__init__.py, pip/_internal/...，
     * 直接解压到 libDir，自然展开成 libDir/pip/...
     * 绕过 AAPT2 深层嵌套目录丢失问题。
     */
    private static void extractPipZip(Context context, File libDir) throws Exception {
        String pipZipAsset = STDLIB_ASSET_DIR + "/pip.zip";
        File pipDir = new File(libDir, "pip");
        // 先清理可能由 extractAssetDir 留下的不完整 pip 目录
        if (pipDir.exists()) deleteRecursive(pipDir);

        InputStream is = null;
        ZipInputStream zis = null;
        int count = 0;
        try {
            is = context.getAssets().open(pipZipAsset);
            zis = new ZipInputStream(is);
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                // zip 内路径形如 "pip/xxx"，解压到 libDir → libDir/pip/xxx
                File outFile = new File(libDir, entry.getName());
                // 防御 zip slip（路径穿越）
                String canonical = outFile.getCanonicalPath();
                if (!canonical.startsWith(libDir.getCanonicalPath() + File.separator)
                    && !canonical.equals(libDir.getCanonicalPath())) {
                    throw new IOException("非法 zip 条目路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally {
                    fos.close();
                }
                zis.closeEntry();
                count++;
            }
        } finally {
            if (zis != null) try { zis.close(); } catch (IOException ignored) {}
            if (is != null) try { is.close(); } catch (IOException ignored) {}
        }
        AppLogger.i("PythonBridge", "pip.zip 解压完成: " + count + " 个文件 → " + pipDir.getAbsolutePath());
        // 校验关键子包
        File internalInit = new File(pipDir, "_internal/__init__.py");
        AppLogger.i("PythonBridge", "pip/_internal/__init__.py 存在: " + internalInit.exists());
    }

    private static boolean isStdlibReady(File dir) {
        // 校验关键文件 + 易丢失的子目录（曾因提取 bug 丢失）
        return dir.exists()
            && new File(dir, "lib/python3.14/os.py").exists()
            && new File(dir, "lib/python3.14/encodings/__init__.py").exists()
            && new File(dir, "lib/python3.14/zipfile/_path/__init__.py").exists()
            && new File(dir, "lib/python3.14/pip/__init__.py").exists()
            && new File(dir, "lib/python3.14/pip/_internal/__init__.py").exists();
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
                AppLogger.i("PythonBridge", indent + f.getName() + "/");
                logFileTree(f, depth + 1);
            } else {
                AppLogger.i("PythonBridge", indent + f.getName() + " (" + f.length() + "B)");
            }
        }
        if (children.length > 20) {
            AppLogger.i("PythonBridge", "  ... 还有 " + (children.length - 20) + " 项");
        }
    }

    /**
     * 判断 asset 路径是文件还是目录：尝试 open，成功是文件，IOException 是目录。
     * 这是最可靠的方式，不依赖 list() 返回值约定或扩展名猜测
     * （stdlib 里有 config-3.14-aarch64-linux-android 这类无扩展名文件，
     * 用扩展名判断会误判为目录导致丢失）。
     */
    private static boolean isAssetFile(Context context, String childPath) {
        InputStream is = null;
        try {
            is = context.getAssets().open(childPath);
            return true;  // 能 open → 文件
        } catch (IOException e) {
            return false;  // 不能 open → 目录
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) {}
        }
    }

    private static void extractAssetDir(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names = context.getAssets().list(assetPath);
        if (names == null) {
            extractAssetFile(context, assetPath, targetDir);
            return;
        }
        if (!targetDir.exists()) targetDir.mkdirs();
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            if (isAssetFile(context, childPath)) {
                extractAssetFile(context, childPath, targetDir);
            } else {
                File childDir = new File(targetDir, name);
                childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            }
        }
    }

    private static void extractAssetDirRecursive(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names = context.getAssets().list(assetPath);
        if (names == null) return;
        for (String name : names) {
            String childPath = assetPath + "/" + name;
            if (isAssetFile(context, childPath)) {
                extractAssetFile(context, childPath, targetDir);
            } else {
                File childDir = new File(targetDir, name);
                childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
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
