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

        ProcessRunner.Result result = ProcessRunner.execShell(command, timeout);

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
     */
    private String executeGitBinary(StringBuilder sb, String gitPath, String gitArgs, int timeout) {
        try {
            ProcessRunner.Result result = ProcessRunner.execShell(gitPath + " " + gitArgs, timeout);
            sb.append("退出码: ").append(result.exitCode).append("\n");
            if (result.timedOut) {
                sb.append("⚠️ 命令超时（").append(timeout).append("秒），已被强制终止\n");
            }
            if (result.truncated) {
                sb.append("⚠️ 输出过长，已截断\n");
            }
            if (!result.stdout.isEmpty()) sb.append("\n").append(result.stdout);
            if (!result.stderr.isEmpty()) sb.append("\n[stderr]\n").append(result.stderr);
            return sb.toString();
        } catch (Exception e) {
            // git 二进制执行失败，回退到 dulwich
            sb.append("[git 二进制执行失败，回退到 dulwich]\n");
            return executeGitDulwich(sb, gitArgs);
        }
    }

    /**
     * 从 assets/git/git 提取内嵌 git 二进制（如果存在）
     * @return git 可执行文件，或 null 如果不存在
     */
    private java.io.File extractEmbeddedGitBinary() {
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
            outFile.setExecutable(true, false);
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
        String result = PythonBridge.exec(pythonCode);
        sb.append(result);
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
        sb.append("说明: 系统未安装 git 二进制，使用三层回退策略:\n");
        sb.append("  1. 系统 git 二进制（当前未找到）\n");
        sb.append("  2. 内嵌 git 二进制（assets/git/git，当前未放置）\n");
        sb.append("  3. dulwich 纯 Python Git（当前生效，首次自动安装）\n\n");
        sb.append("用法: 在 shell 中直接使用 git 命令即可，如:\n")
          .append("  git clone <url>\n")
          .append("  git add -A && git commit -m \"msg\" && git push\n")
          .append("  git status / git log / git branch\n");
        return sb.toString();
    }
}
