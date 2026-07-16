package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shell 命令执行工具 - 优化版
 *
 * 改进：
 * 1. 移除不可靠的黑名单（靠 Android 权限系统）
 * 2. 使用 ProcessRunner 统一进程管理（安全超时、防泄漏、输出限制）
 * 3. 移除调试信息泄露
 * 4. 输出格式精简
 */
public class ShellTool implements Tool {

    private Context context;

    public ShellTool() {}

    public ShellTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return "在 Android 系统上执行 shell 命令（sh -c），支持管道、重定向等。" +
                "常用命令: ls, cat, echo, ps, grep, find, stat, mkdir, mv, cp, touch, kill 等。" +
                "已集成: python/pip（内嵌 Python 3.14.6）、git（内嵌 dulwich，首次自动安装）";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject command = new JSONObject();
            command.put("type", "string");
            command.put("description", "要执行的 shell 命令");
            properties.put("command", command);

            JSONObject timeout = new JSONObject();
            timeout.put("type", "integer");
            timeout.put("description", "超时时间（秒），默认 30");
            timeout.put("default", 30);
            properties.put("timeout", timeout);

            schema.put("properties", properties);

            JSONArray requiredArray = new JSONArray();
            requiredArray.put("command");
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String command = arguments.getString("command");
        int timeout = arguments.has("timeout") ? arguments.getInt("timeout") : 30;

        if (command == null || command.trim().isEmpty()) {
            throw new Exception("命令不能为空");
        }

        // 截获 python/pip 命令，桥接到内嵌 Python 环境
        String trimmed = command.trim();
        if (trimmed.startsWith("python3 ") || trimmed.startsWith("python ") || trimmed.equals("python3") || trimmed.equals("python")) {
            return executePython(trimmed, timeout);
        }
        if (trimmed.startsWith("pip ") || trimmed.equals("pip")) {
            return executePython("python -m " + trimmed, timeout);
        }
        // 拦截 which/command -v 查询 python/pip：嵌入式 Python 不在系统 PATH，
        // which 必然找不到，直接返回内嵌环境状态避免误导
        if (isPythonPathQuery(trimmed)) {
            return reportEmbeddedPython(command);
        }
        // 拦截 git 命令，桥接到内嵌 Python + dulwich（纯 Python Git 实现）
        if (trimmed.equals("git") || trimmed.startsWith("git ")) {
            return executeGit(trimmed, timeout);
        }
        // 拦截 which/command -v 查询 git
        if ((trimmed.startsWith("which git") || trimmed.startsWith("command -v git") || trimmed.startsWith("type git"))) {
            return reportEmbeddedGit(command);
        }

        // 对非拦截的 shell 命令，注入 git 函数定义，让复合命令中的 git 调用也能工作
        // 例如 "cd /dir && git status" 中的 git 会被解析为函数，调用 nativeLibraryDir/libgit.so
        String wrappedCommand = wrapCommandWithGitFunction(command);
        ProcessRunner.Result result = ProcessRunner.execShell(wrappedCommand, timeout);

        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append("\n");
        sb.append("退出码: ").append(result.exitCode).append("\n");

        if (result.timedOut) {
            sb.append("⚠️ 命令超时（").append(timeout).append("秒），已被强制终止\n");
        }
        if (result.truncated) {
            sb.append("⚠️ 输出过长，已截断\n");
        }

        if (!result.stdout.isEmpty()) {
            sb.append("\n").append(result.stdout);
        }
        if (!result.stderr.isEmpty()) {
            sb.append("\n[stderr]\n").append(result.stderr);
        }

        // 失败时附加简要诊断提示
        if (result.exitCode != 0 && !result.timedOut) {
            sb.append("\n提示: 命令失败。注意：Python 请直接用 python 工具执行，不要用 shell 调用");
        }

        return sb.toString();
    }

    /**
     * 在 shell 命令前注入 git 函数定义和环境变量，让复合命令中的 git 调用
     * （如 "cd /dir && git status"）也能使用内嵌静态 git 二进制，且能找到
     * git-remote-https helper（HTTPS clone/push 需要）。
     * 函数定义: git() { export GIT_EXEC_PATH=...; /path/to/libgit.so "$@"; }
     * 如果内嵌 git 不可用，返回原命令。
     */
    private String wrapCommandWithGitFunction(String command) {
        if (context == null) return command;
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            java.io.File libGit = new java.io.File(nativeLibDir, "libgit.so");
            if (libGit.exists() && libGit.canExecute()) {
                // 确保 GIT_EXEC_PATH 就绪（含 git-remote-https 符号链接）
                String execPath = ensureGitExecPath();
                String filesDir = context.getFilesDir().getAbsolutePath();
                // 确保 .gitconfig 含 sslVerify=false（避免静态 OpenSSL 加载 CA 段错误）
                ensureCacertBundle();
                ensureGitConfig(null);
                StringBuilder prefix = new StringBuilder();
                // 注入环境变量
                if (execPath != null) {
                    prefix.append("export GIT_EXEC_PATH=").append(execPath).append("; ");
                }
                prefix.append("export HOME=").append(filesDir).append("; ");
                prefix.append("export GIT_TEMPLATE_DIR=''; ");
                prefix.append("export GIT_DNS_SERVERS='8.8.8.8,8.8.4.4,1.1.1.1'; ");
                // 注入 git 函数
                prefix.append("git() { ").append(libGit.getAbsolutePath()).append(" \"$@\"; }; ");
                return prefix + command;
            }
        } catch (Exception ignored) {}
        return command;
    }

    /**
     * 桥接到内嵌 Python 环境处理 python 命令
     */
    private String executePython(String cmd, int timeout) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(cmd).append("\n");
        
        String args = cmd.replaceFirst("^python3?\\s*", "").trim();
        
        try {
            String code;
            if (args.startsWith("-c ")) {
                code = args.substring(3).trim();
                if ((code.startsWith("\"") && code.endsWith("\"")) || 
                    (code.startsWith("'") && code.endsWith("'"))) {
                    code = code.substring(1, code.length() - 1);
                }
            } else if (args.startsWith("-m ")) {
                // python -m module args — 执行模块
                // moduleArgs 形如 "pip install requests"，需拆成模块名 + 参数
                String moduleArgs = args.substring(3).trim();
                String[] parts = moduleArgs.split("\\s+", 2);
                String moduleName = parts[0];
                // sys.argv[0] 是模块名，后续是参数。用 Python list 字面量构造，避免转义问题
                // 注意：原代码 ['pip install requests'].split() 错误地对 list 调 split，
                // 改为对字符串 split：sys.argv = 'pip install requests'.split()
                code = "import sys, runpy; sys.argv = '" + moduleArgs.replace("'", "\\'") + "'.split(); runpy.run_module('" + moduleName.replace("'", "\\'") + "', run_name='__main__')";
            } else if (!args.isEmpty()) {
                String path = args;
                java.io.File file = new java.io.File(path);
                if (!file.isAbsolute()) {
                    file = new java.io.File("/sdcard", path);
                }
                if (!file.exists()) {
                    return sb.toString() + "错误: 文件不存在: " + file.getAbsolutePath();
                }
                code = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            } else {
                return sb.toString() + 
                    "内嵌 Python 环境已就绪。请使用 python -c \"代码\" 执行单行代码，\n" +
                    "或通过 python 工具（tools/call）执行多行代码。\n";
            }
            
            String result = execPythonCode(code);
            sb.append(result);
        } catch (Exception e) {
            sb.append("错误: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 执行 Python 代码前先确保 PythonBridge 已初始化。
     * shell 截获的 python/pip 命令必须先 init，否则 exec 返回
     * "Python 未初始化"（jniInitOk 保持 false，jniInitRetCode 为默认 0）。
     */
    private String execPythonCode(String code) throws Exception {
        if (context == null) {
            return "[错误] ShellTool 未注入 Context，无法初始化 Python，请重启应用";
        }
        PythonBridge.init(context);
        return PythonBridge.exec(code);
    }

    /**
     * 判断是否为查询 python/pip 路径的命令（which / command -v / type）
     */
    private boolean isPythonPathQuery(String cmd) {
        // 匹配 "which python", "which python3", "which pip",
        // "command -v python", "type python" 等
        return (cmd.startsWith("which ") || cmd.startsWith("command -v ") || cmd.startsWith("type "))
            && (cmd.contains("python") || cmd.contains("pip"));
    }

    /**
     * 对 which python 等查询返回内嵌 Python 环境说明，
     * 避免系统 PATH 找不到 python 造成"未安装"的误解。
     */
    private String reportEmbeddedPython(String originalCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(originalCmd).append("\n");
        sb.append("退出码: 0\n\n");
        sb.append("说明: 本应用使用 JNI 内嵌 Python（Python 3.14.6），")
          .append("没有独立的 python 可执行文件，不在系统 PATH 中，")
          .append("所以 which/command -v 找不到。\n\n");
        sb.append("状态: ").append(PythonBridge.getStatus()).append("\n");
        sb.append("用法: \n")
          .append("  - 直接用 python 工具（tools/call name=python）执行代码\n")
          .append("  - shell 里用 `python -c \"代码\"` 或 `pip install 包名` 会被桥接到内嵌环境\n");
        return sb.toString();
    }

    // ===== Git 集成（三层回退：系统 git → 内嵌 git 二进制 → dulwich）=====

    /** 系统可能存在 git 的路径 */
    private static final String[] SYSTEM_GIT_PATHS = {
        "/system/bin/git", "/system/xbin/git",
        "/vendor/bin/git", "/data/local/tmp/git",
        "/data/data/com.termux/files/usr/bin/git",
        "/usr/bin/git", "/usr/local/bin/git",
    };

    /** 缓存找到的 git 路径，避免每次都搜索 */
    private static String cachedGitPath = null;
    private static boolean gitPathSearched = false;

    /**
     * 拦截 git 命令，三层回退策略：
     * 1. 系统 git 二进制（rooted 设备或 Termux 环境）
     * 2. 内嵌 git 二进制（assets/git/git 打包进 APK，需手动放置）
     * 3. dulwich 纯 Python Git（自动 pip install）
     */
    private String executeGit(String cmd, int timeout) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(cmd).append("\n");

        String gitArgs = cmd.replaceFirst("^git\\s+", "").trim();
        if (gitArgs.isEmpty()) {
            return sb.toString() + "用法: git <子命令> [参数...]\n"
                + "支持: init, clone, add, commit, status, log, push, pull, fetch, "
                + "branch, checkout, remote, config, tag, diff, rm, version\n"
                + "策略: 优先用系统 git 二进制，回退到内嵌 dulwich";
        }

        // 第 1 层：系统 git 二进制
        String gitBin = findGitBinary();
        if (gitBin != null) {
            return executeGitBinary(sb, gitBin, gitArgs, timeout);
        }

        // 第 2 层：内嵌 git 二进制（assets/git/git）
        if (context != null) {
            java.io.File embeddedGit = extractEmbeddedGitBinary();
            if (embeddedGit != null && embeddedGit.canExecute()) {
                return executeGitBinary(sb, embeddedGit.getAbsolutePath(), gitArgs, timeout);
            }
        }

        // 第 3 层：dulwich 回退
        return executeGitDulwich(sb, gitArgs);
    }

    /**
     * 查找系统 git 二进制
     * @return git 路径，或 null 如果未找到
     */
    private String findGitBinary() {
        if (gitPathSearched) return cachedGitPath;
        gitPathSearched = true;

        // 检查预定义路径
        for (String path : SYSTEM_GIT_PATHS) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canExecute()) {
                cachedGitPath = path;
                return path;
            }
        }

        // 用 which/command -v 搜索 PATH
        try {
            ProcessRunner.Result r = ProcessRunner.execShell("command -v git 2>/dev/null || which git 2>/dev/null", 5);
            if (r.exitCode == 0) {
                String found = r.stdout.trim();
                if (!found.isEmpty()) {
                    java.io.File f = new java.io.File(found);
                    if (f.exists() && f.canExecute()) {
                        cachedGitPath = found;
                        return found;
                    }
                }
            }
        } catch (Exception ignored) {}

        cachedGitPath = null;
        return null;
    }

    /**
     * 用真正的 git 二进制执行命令
     * 注意：不能用 sh -c，因为 filesDir 下的文件 SELinux 标记为 app_data_file，
     * sh 拒绝执行（退出码 126 Permission denied）。必须用 Runtime.exec 直接 execve
     * nativeLibraryDir/libgit.so（标记为 app_lib_data_file，允许执行）。
     * 同时设置 GIT_EXEC_PATH 让 git 找到 git-remote-https 等 helper。
     */
    private String executeGitBinary(StringBuilder sb, String gitPath, String gitArgs, int timeout) {
        try {
            // 确保 GIT_EXEC_PATH 目录就绪（含 git-remote-https 符号链接）
            java.util.Map<String, String> env = buildGitEnv();

            // 用 shlex 风格分割参数（支持引号），避免 sh -c 的 SELinux 问题
            String[] args = splitShellArgs(gitArgs);
            String[] cmd = new String[args.length + 1];
            cmd[0] = gitPath;
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessRunner.Result result = ProcessRunner.exec(cmd, env, timeout);
            sb.append("退出码: ").append(result.exitCode).append("\n");
            if (result.timedOut) {
                sb.append("⚠️ 命令超时（").append(timeout).append("秒），已被强制终止\n");
            }
            if (result.truncated) {
                sb.append("⚠️ 输出过长，已截断\n");
            }
            if (!result.stdout.isEmpty()) sb.append("\n").append(result.stdout);
            if (!result.stderr.isEmpty()) sb.append("\n[stderr]\n").append(result.stderr);
            // git clone/fetch/push https:// 失败且包含 "remote helper" 时，附加诊断信息
            if (result.exitCode != 0 && (result.stderr.contains("remote helper") || result.stdout.contains("remote helper"))) {
                sb.append("\n--- git helper 诊断 ---\n");
                sb.append(diagnoseGitHelper());
            }
            return sb.toString();
        } catch (Exception e) {
            // git 二进制执行失败，回退到 dulwich
            sb.append("[git 二进制执行失败: ").append(e.getMessage()).append("，回退到 dulwich]\n");
            return executeGitDulwich(sb, gitArgs);
        }
    }

    /**
     * 诊断 git-remote-https helper 的可用性，帮助定位 "unable to find remote helper" 问题。
     */
    private String diagnoseGitHelper() {
        StringBuilder d = new StringBuilder();
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            java.io.File helperSo = new java.io.File(nativeLibDir, "libgitremotehttps.so");
            d.append("nativeLibraryDir: ").append(nativeLibDir).append("\n");
            d.append("libgitremotehttps.so: exists=").append(helperSo.exists())
             .append(" canExec=").append(helperSo.canExecute()).append("\n");

            java.io.File execDir = new java.io.File(context.getFilesDir(), "git-exec");
            d.append("GIT_EXEC_PATH: ").append(execDir.getAbsolutePath()).append("\n");
            d.append("execDir exists: ").append(execDir.exists()).append("\n");
            if (execDir.exists()) {
                java.io.File helperLink = new java.io.File(execDir, "git-remote-https");
                d.append("git-remote-https link exists(File.exists): ").append(helperLink.exists()).append("\n");
                try {
                    String lt = android.system.Os.readlink(helperLink.getAbsolutePath());
                    d.append("git-remote-https readlink: ").append(lt).append("\n");
                    java.io.File ltFile = new java.io.File(lt);
                    d.append("  target exists: ").append(ltFile.exists()).append("\n");
                } catch (Exception e) {
                    d.append("git-remote-https readlink 失败: ").append(e.getMessage()).append("\n");
                }
                // 列出 execDir 内容
                d.append("execDir 内容:\n");
                String[] files = execDir.list();
                if (files != null) {
                    for (String f : files) d.append("  ").append(f).append("\n");
                }
            }
            // 列出 nativeLibraryDir 中的 git 相关 .so
            d.append("nativeLibraryDir git 相关文件:\n");
            java.io.File nld = new java.io.File(nativeLibDir);
            String[] nldFiles = nld.list();
            if (nldFiles != null) {
                for (String f : nldFiles) {
                    if (f.contains("git")) d.append("  ").append(f).append("\n");
                }
            }
        } catch (Exception e) {
            d.append("诊断异常: ").append(e.getMessage()).append("\n");
        }
        return d.toString();
    }

    /**
     * 简单的 shell 参数分割：按空白分割，支持单引号/双引号包围含空白的参数
     */
    private static String[] splitShellArgs(String s) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return new String[0];
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result.toArray(new String[0]);
    }

    /**
     * 构建 git 运行环境变量：
     * - GIT_EXEC_PATH: 指向 filesDir/git-exec/，内含符号链接 git-remote-https → nativeLibraryDir/libgitremotehttps.so
     * - HOME: 指向 filesDir（git 需要写 ~/.gitconfig 等）
     * - GIT_TEMPLATE_DIR: 设为空避免 "templates not found" 警告
     * - SSL_CERT_FILE: 内嵌 Mozilla CA 证书包（静态 OpenSSL 无内置 CA 路径）
     * - GIT_DNS_SERVERS: c-ares DNS 服务器（静态 libc getaddrinfo 不工作）
     * execve 符号链接时 SELinux 检查目标文件（app_lib_data_file 允许执行），不是链接本身。
     */
    private java.util.Map<String, String> buildGitEnv() {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        try {
            String execPath = ensureGitExecPath();
            if (execPath != null) {
                env.put("GIT_EXEC_PATH", execPath);
            }
            // git 需要 HOME 来写 .gitconfig
            env.put("HOME", context.getFilesDir().getAbsolutePath());
            // 避免 "templates not found in /tmp/git-install/share/git-core/templates" 警告
            env.put("GIT_TEMPLATE_DIR", "");
            // c-ares DNS 服务器（Android 静态二进制的 getaddrinfo 不工作，
            // curl 编译时启用 c-ares，通过此环境变量设置 DNS 服务器）
            env.put("GIT_DNS_SERVERS", "8.8.8.8,8.8.4.4,1.1.1.1");
            // SSL 证书: 静态 OpenSSL 加载 cacert.pem 时段错误（OpenSSL 3.x 静态构建
            // 在 Android 上的 file provider bug）。临时方案：写 .gitconfig 设置
            // http.sslVerify=false 完全跳过 SSL 验证，不设置任何 CA 文件路径。
            // 这样 git http.c 不会调用 CURLOPT_CAINFO，libcurl 不会尝试加载 PEM 文件。
            // TODO: 修复静态 OpenSSL CA 加载崩溃后恢复 sslVerify=true + sslCAInfo
            ensureCacertBundle(); // 仍提取 cacert.pem 供后续使用
            ensureGitConfig(null);
        } catch (Exception ignored) {}
        return env;
    }

    /**
     * 从 assets/git/cacert.pem 提取 Mozilla CA 证书包到 filesDir/cacert.pem。
     * 静态 OpenSSL 没有内置 CA 路径，需要显式提供 CA 证书用于 HTTPS 验证。
     * @return cacert.pem 路径，或 null 如果提取失败
     */
    private String ensureCacertBundle() {
        if (context == null) return null;
        try {
            java.io.File caFile = new java.io.File(context.getFilesDir(), "cacert.pem");
            // 已提取则直接用（检查大小 >1KB 防止空文件）
            if (caFile.exists() && caFile.length() > 1024) {
                return caFile.getAbsolutePath();
            }
            // 从 assets 提取
            android.content.res.AssetManager am = context.getAssets();
            try {
                java.io.InputStream is = am.open("git/cacert.pem");
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(caFile);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                        fos.flush();
                    } finally { fos.close(); }
                } finally { is.close(); }
            } catch (java.io.IOException e) {
                return null; // assets 中没有 cacert.pem
            }
            if (caFile.length() > 1024) {
                return caFile.getAbsolutePath();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写 .gitconfig 设置 http.sslCAInfo + sslVerify。
     * v2.4.14 用 sslCAInfo 导致段错误（OpenSSL 静态库加载 PEM 文件时崩溃），
     * 这里改用 sslVerify=false 绕过验证（临时诊断方案），同时保留 sslCAInfo
     * 供后续修复后启用。
     * TODO: 找到静态 OpenSSL 加载 CA 证书崩溃的根因后恢复 sslVerify=true
     */
    private void ensureGitConfig(String caBundlePath) {
        if (context == null) return;
        try {
            java.io.File gitconfig = new java.io.File(context.getFilesDir(), ".gitconfig");
            String marker = "# agent-toolbox git config v2";
            // 简单策略：删除旧 .gitconfig 重新写（app 管理的配置，用户自定义会被覆盖）
            // 这样能确保 v2.4.14 写的错误 sslCAInfo 配置被清除
            if (gitconfig.exists()) {
                try {
                    String old = new String(java.nio.file.Files.readAllBytes(gitconfig.toPath()), "UTF-8");
                    if (old.contains(marker)) return; // 已是 v2 配置，跳过
                } catch (Exception ignored) {}
                gitconfig.delete(); // 删除旧配置（含 v2.4.14 的错误配置）
            }
            StringBuilder sb = new StringBuilder();
            sb.append(marker).append("\n");
            sb.append("[http]\n");
            // 临时禁用 SSL 验证：静态 OpenSSL 加载 cacert.pem 时段错误，
            // 先禁用验证让 clone 能工作，后续再修复 CA 加载问题。
            // 注意: 不设置 sslCAInfo，因为 git http.c 会无视 sslVerify=false
            // 仍调用 CURLOPT_CAINFO 加载 CA 文件导致段错误。
            sb.append("\tsslVerify = false\n");
            java.nio.file.Files.write(gitconfig.toPath(), sb.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    /**
     * 确保 GIT_EXEC_PATH 目录就绪：
     * 在 filesDir/git-exec/ 创建符号链接，指向 nativeLibraryDir 中的 .so 文件。
     * - git → libgit.so
     * - git-remote-https → libgitremotehttps.so
     * 符号链接本身在 filesDir（app_data_file），但 execve 时 SELinux 检查目标
     * （nativeLibraryDir 中的 app_lib_data_file，允许执行）。
     * @return GIT_EXEC_PATH 路径，或 null 如果失败
     */
    private String ensureGitExecPath() {
        if (context == null) return null;
        try {
            java.io.File execDir = new java.io.File(context.getFilesDir(), "git-exec");
            if (!execDir.exists()) execDir.mkdirs();

            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            // 创建 git-remote-https → libgitremotehttps.so 符号链接
            createSymlink(new java.io.File(nativeLibDir, "libgitremotehttps.so"),
                         new java.io.File(execDir, "git-remote-https"));
            // 创建 git → libgit.so 符号链接（git 子命令查找时需要）
            createSymlink(new java.io.File(nativeLibDir, "libgit.so"),
                         new java.io.File(execDir, "git"));

            return execDir.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 创建符号链接，如果已存在且指向正确则跳过。
     * 关键: 不能用 File.exists() 判断链接是否存在——它对**断链**返回 false
     * （断链 = 符号链接本身存在但目标已被删除）。app 更新后 nativeLibraryDir 路径
     * 变化，旧符号链接变成断链，File.exists() 返回 false → 跳过删除 →
     * Os.symlink() 因 EEXIST 失败 → 断链残留 → git 找不到 helper。
     * 修复: 用 Os.readlink() 检测（对断链也能读取链接路径），无条件删除后再创建。
     */
    private void createSymlink(java.io.File target, java.io.File link) {
        try {
            // 用 readlink 检测是否已是正确的符号链接（对断链也有效）
            try {
                String linkTarget = android.system.Os.readlink(link.getAbsolutePath());
                if (linkTarget.equals(target.getAbsolutePath())) {
                    return; // 已正确链接（即使目标是断链也不需要改，路径相同）
                }
            } catch (Exception ignored) {
                // readlink 失败 = 不是符号链接或不存在，继续创建
            }
            // 无条件删除已存在的文件/符号链接（File.delete 对断链也能 unlink）
            link.delete();
            try { android.system.Os.remove(link.getAbsolutePath()); } catch (Exception ignored) {}
            // 创建新符号链接
            android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception e) {
            // 符号链接创建失败（可能不支持），静默失败
        }
    }

    /**
     * 获取内嵌 git 二进制路径。
     * 优先使用 nativeLibraryDir/libgit.so（由 jniLibs 打包，AGP 自动解压并设置可执行权限，
     * SELinux 标记为 app_lib_data_file 允许执行）。
     * 回退到从 assets/git/git 提取到 filesDir/git_bin（可能因 SELinux 不可执行）。
     * @return git 可执行文件，或 null 如果不可用
     */
    private java.io.File extractEmbeddedGitBinary() {
        // 优先：nativeLibraryDir/libgit.so（APK 安装时自动解压，SELinux 允许执行）
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            java.io.File libGit = new java.io.File(nativeLibDir, "libgit.so");
            if (libGit.exists() && libGit.canExecute()) {
                return libGit;
            }
        } catch (Exception ignored) {}

        // 回退：从 assets/git/git 提取到 filesDir/git_bin（可能因 SELinux 不可执行）
        try {
            java.io.File outFile = new java.io.File(context.getFilesDir(), "git_bin");
            // 检查 assets 中是否有 git 二进制
            android.content.res.AssetManager am = context.getAssets();
            try {
                am.open("git/git");
            } catch (java.io.IOException e) {
                return null; // assets 中没有 git 二进制
            }
            // 已提取且可执行则直接用
            if (outFile.exists() && outFile.canExecute()) {
                return outFile;
            }
            // 提取
            java.io.InputStream is = am.open("git/git");
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally { fos.close(); }
            } finally { is.close(); }
            // setExecutable 在某些 Android 设备/文件系统上静默失败，用 chmod 755 强制设置
            outFile.setExecutable(true, false);
            try {
                new ProcessBuilder("chmod", "755", outFile.getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {}
            // 验证权限确实设置成功
            if (!outFile.canExecute()) {
                return null; // 权限设置失败，回退到 dulwich
            }
            return outFile;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用 dulwich（纯 Python Git）执行 git 命令
     */
    private String executeGitDulwich(StringBuilder sb, String gitArgs) {
        if (context == null) {
            return sb.toString() + "[错误] ShellTool 未注入 Context，无法初始化 Python";
        }
        try {
            PythonBridge.init(context);
        } catch (Exception e) {
            return sb.toString() + "[错误] Python 初始化失败: " + e.getMessage();
        }

        java.io.File bridgeScript = extractGitBridge();
        if (bridgeScript == null) {
            return sb.toString() + "[错误] 无法提取 git_bridge.py";
        }

        sb.append("[dulwich 模式]\n");
        String pythonCode = buildGitExecCode(bridgeScript.getAbsolutePath(), gitArgs);
        try {
            String result = PythonBridge.exec(pythonCode);
            sb.append(result);
        } catch (Exception e) {
            sb.append("[错误] dulwich 执行失败: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 构造执行 git_bridge.py 的 Python 代码
     * 通过 sys.argv 传递参数，类似命令行调用
     * 将 stderr 重定向到 stdout，确保 JNI 层捕获所有输出
     */
    private String buildGitExecCode(String scriptPath, String gitArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("import sys, os\n");
        // 将 stderr 重定向到 stdout，因为 nativeExec 可能只捕获 stdout
        sb.append("sys.stderr = sys.stdout\n");
        // 用 shlex.split 解析参数（支持引号）
        sb.append("sys.argv = ['git'] + __import__('shlex').split(");
        String escaped = gitArgs.replace("\\", "\\\\").replace("'", "\\'");
        sb.append("'").append(escaped).append("'");
        sb.append(")\n");
        // 设置脚本所在目录到 sys.path
        sb.append("script_dir = os.path.dirname('").append(scriptPath.replace("\\", "\\\\")).append("')\n");
        sb.append("if script_dir not in sys.path: sys.path.insert(0, script_dir)\n");
        // 执行脚本（用 exec，不依赖 __name__ == '__main__'）
        sb.append("exec(open('").append(scriptPath.replace("\\", "\\\\")).append("', encoding='utf-8').read())\n");
        return sb.toString();
    }

    /**
     * 从 assets 提取 git_bridge.py 到 filesDir
     * @return 脚本文件，或 null 如果失败
     */
    private java.io.File extractGitBridge() {
        try {
            java.io.File outFile = new java.io.File(context.getFilesDir(), "git_bridge.py");
            // 如果已存在且与 assets 大小一致则跳过（简单判断）
            if (outFile.exists() && outFile.length() > 0) {
                return outFile;
            }
            android.content.res.AssetManager am = context.getAssets();
            java.io.InputStream is = am.open("python/git_bridge.py");
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                } finally { fos.close(); }
            } finally { is.close(); }
            return outFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 对 which git 等查询返回 Git 集成状态说明
     */
    private String reportEmbeddedGit(String originalCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(originalCmd).append("\n");
        sb.append("退出码: 0\n\n");
        // 检查系统 git 是否可用
        String gitBin = findGitBinary();
        if (gitBin != null) {
            sb.append("已找到系统 git: ").append(gitBin).append("\n");
            sb.append("git 命令将直接使用此二进制执行。\n");
            return sb.toString();
        }
        // 检查内嵌 git 二进制是否可用（优先 nativeLibraryDir/libgit.so）
        boolean hasEmbeddedGit = false;
        String embeddedGitPath = null;
        if (context != null) {
            try {
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                java.io.File libGit = new java.io.File(nativeLibDir, "libgit.so");
                if (libGit.exists() && libGit.canExecute()) {
                    hasEmbeddedGit = true;
                    embeddedGitPath = libGit.getAbsolutePath();
                }
            } catch (Exception ignored) {}
            if (!hasEmbeddedGit) {
                try {
                    context.getAssets().open("git/git").close();
                    hasEmbeddedGit = true;
                } catch (java.io.IOException ignored) {}
            }
        }
        sb.append("说明: 系统未安装 git 二进制，使用三层回退策略:\n");
        sb.append("  1. 系统 git 二进制（当前未找到）\n");
        if (hasEmbeddedGit) {
            if (embeddedGitPath != null) {
                sb.append("  2. 内嵌 git 二进制（").append(embeddedGitPath).append("，已就绪，4.2MB 静态二进制）\n");
            } else {
                sb.append("  2. 内嵌 git 二进制（assets/git/git，已就绪，4.2MB 静态二进制）\n");
            }
            sb.append("  3. dulwich 纯 Python Git（备用，首次自动安装）\n\n");
            sb.append("当前生效: 第 2 层（内嵌静态 git 二进制）\n");
        } else {
            sb.append("  2. 内嵌 git 二进制（assets/git/git，当前未放置）\n");
            sb.append("  3. dulwich 纯 Python Git（当前生效，首次自动安装）\n\n");
            sb.append("当前生效: 第 3 层（dulwich）\n");
        }
        sb.append("\n用法: 在 shell 中直接使用 git 命令即可，如:\n")
          .append("  git clone <url>\n")
          .append("  git add -A && git commit -m \"msg\" && git push\n")
          .append("  git status / git log / git branch\n");
        return sb.toString();
    }
}
