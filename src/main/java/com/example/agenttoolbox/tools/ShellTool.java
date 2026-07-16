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

    // ===== Git 桥接（dulwich — 纯 Python Git 实现）=====

    /**
     * 拦截 git 命令，通过内嵌 Python + dulwich 执行。
     * 首次使用时自动 pip install dulwich。
     */
    private String executeGit(String cmd, int timeout) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(cmd).append("\n");

        if (context == null) {
            return sb.toString() + "[错误] ShellTool 未注入 Context，无法初始化 Python";
        }
        // 确保 Python 环境就绪
        PythonBridge.init(context);

        // 将 git_bridge.py 从 assets 提取到 filesDir
        java.io.File bridgeScript = extractGitBridge();
        if (bridgeScript == null) {
            return sb.toString() + "[错误] 无法提取 git_bridge.py";
        }

        // 解析 git 子命令和参数，构造 Python 执行代码
        // cmd 形如 "git clone https://..." → 去掉 "git " 前缀
        String gitArgs = cmd.replaceFirst("^git\\s+", "").trim();
        if (gitArgs.isEmpty()) {
            return sb.toString() + "用法: git <子命令> [参数...]\n"
                + "支持: init, clone, add, commit, status, log, push, pull, fetch, "
                + "branch, checkout, remote, config, tag, diff, rm, version\n"
                + "（基于 dulwich 纯 Python Git 实现，首次使用自动安装）";
        }

        // 用 Python exec 执行脚本，通过 sys.argv 传递参数
        // 用 exec() 方式调用脚本文件，设置 sys.argv
        String pythonCode = buildGitExecCode(bridgeScript.getAbsolutePath(), gitArgs);
        String result = PythonBridge.exec(pythonCode);
        sb.append(result);
        return sb.toString();
    }

    /**
     * 构造执行 git_bridge.py 的 Python 代码
     * 通过 sys.argv 传递参数，类似命令行调用
     */
    private String buildGitExecCode(String scriptPath, String gitArgs) {
        // 将 gitArgs 按空格拆分为参数列表（简单实现，支持引号）
        // 用 Python 的 shlex.split 更可靠
        StringBuilder sb = new StringBuilder();
        sb.append("import sys, os, runpy\n");
        sb.append("sys.argv = ['git'] + __import__('shlex').split(");
        // 转义引号
        String escaped = gitArgs.replace("\\", "\\\\").replace("'", "\\'");
        sb.append("'").append(escaped).append("'");
        sb.append(")\n");
        // 设置脚本所在目录到 sys.path
        sb.append("script_dir = os.path.dirname('").append(scriptPath.replace("\\", "\\\\")).append("')\n");
        sb.append("if script_dir not in sys.path: sys.path.insert(0, script_dir)\n");
        // 执行脚本
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
     * 对 which git 等查询返回内嵌 Git 环境说明
     */
    private String reportEmbeddedGit(String originalCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(originalCmd).append("\n");
        sb.append("退出码: 0\n\n");
        sb.append("说明: 本应用通过内嵌 Python + dulwich 提供 Git 支持，")
          .append("没有独立的 git 可执行文件，不在系统 PATH 中。\n\n");
        sb.append("用法: 在 shell 中直接使用 git 命令即可，如:\n")
          .append("  git clone <url>\n")
          .append("  git add -A && git commit -m \"msg\" && git push\n")
          .append("  git status / git log / git branch\n\n");
        sb.append("首次使用会自动 pip install dulwich。");
        return sb.toString();
    }
}
