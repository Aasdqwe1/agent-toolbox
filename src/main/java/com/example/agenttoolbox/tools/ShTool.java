package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * SH Shell 脚本执行工具 - 优化版
 *
 * 改进：
 * 1. 安全执行：用 String[] exec 替代字符串拼接，消除命令注入
 * 2. 路径安全：统一使用 FilePathResolver，禁止 ..
 * 3. 临时文件：执行后立即删除，不用 deleteOnExit
 * 4. 进程管理：使用 ProcessRunner（安全超时、防泄漏、输出限制）
 * 5. 移除双层 sh -c 包装
 */
public class ShTool implements Tool {

    @Override
    public String getName() {
        return "sh";
    }

    @Override
    public String getDescription() {
        return "执行 shell 脚本文件（.sh）或内联脚本内容。" +
                "路径支持：1) 相对路径（内部存储）；2) /storage/emulated/0/...；3) 以 #! 开头的内联脚本内容";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject script = new JSONObject();
            script.put("type", "string");
            script.put("description", "脚本路径（.sh 文件）或内联脚本内容（以 #! 开头）");
            properties.put("script", script);

            JSONObject args = new JSONObject();
            args.put("type", "string");
            args.put("description", "传递给脚本的命令行参数（可选，多个参数用空格分隔）");
            properties.put("args", args);

            JSONObject timeout = new JSONObject();
            timeout.put("type", "integer");
            timeout.put("description", "超时时间（秒），默认 60");
            timeout.put("default", 60);
            properties.put("timeout", timeout);

            schema.put("properties", properties);

            JSONArray requiredArray = new JSONArray();
            requiredArray.put("script");
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String script = arguments.getString("script");
        String args = arguments.has("args") ? arguments.getString("args") : "";
        int timeout = arguments.has("timeout") ? arguments.getInt("timeout") : 60;

        if (script == null || script.trim().isEmpty()) {
            throw new Exception("脚本不能为空");
        }

        boolean isInlineScript = script.trim().startsWith("#!");
        File scriptFile = null;
        boolean needCleanup = false;

        try {
            if (isInlineScript) {
                // 内联脚本：写入临时文件
                scriptFile = createTempScript(script);
                needCleanup = true;
            } else {
                // 文件路径模式：用 FilePathResolver 解析
                scriptFile = FilePathResolver.resolveForRead(script);
                if (!scriptFile.exists()) {
                    throw new Exception("脚本文件不存在: " + scriptFile.getAbsolutePath());
                }
                if (!scriptFile.canRead()) {
                    throw new Exception("脚本文件不可读，请检查权限");
                }
                if (scriptFile.isDirectory()) {
                    throw new Exception("这是一个目录，不是脚本文件");
                }
            }

            // 构建安全的命令数组（无注入风险）
            // sh /path/script.sh arg1 arg2
            String[] cmd = buildCommand(scriptFile.getAbsolutePath(), args);

            ProcessRunner.Result result = ProcessRunner.exec(cmd, timeout);

            // 构建输出
            StringBuilder sb = new StringBuilder();
            sb.append("脚本: ").append(isInlineScript ? "[内联脚本]" : scriptFile.getName()).append("\n");
            sb.append("退出码: ").append(result.exitCode).append("\n");

            if (result.timedOut) {
                sb.append("⚠️ 脚本超时（").append(timeout).append("秒），已被强制终止\n");
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

            return sb.toString();

        } finally {
            // 清理临时文件
            if (needCleanup && scriptFile != null) {
                try {
                    scriptFile.delete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 创建临时脚本文件
     */
    private File createTempScript(String scriptContent) throws Exception {
        File tempDir = new File(FilePathResolver.getBaseDir());
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File scriptFile = new File(tempDir, "tmp_" + System.currentTimeMillis() + ".sh");

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(scriptFile);
            fos.write(scriptContent.getBytes("UTF-8"));
            fos.flush();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }

        scriptFile.setExecutable(true, false);
        return scriptFile;
    }

    /**
     * 构建安全的命令数组
     *
     * 用 String[] 方式执行，避免：
     * 1. 字符串拼接导致的命令注入
     * 2. 双层 sh -c 的多余开销
     *
     * args 按空格分割为独立参数，支持基本的引号内空格
     */
    private String[] buildCommand(String scriptPath, String args) {
        if (args == null || args.trim().isEmpty()) {
            return new String[]{"sh", scriptPath};
        }

        // 简单分割参数（按空格，但保留引号内的空格）
        String[] argParts = splitArgs(args);
        String[] cmd = new String[2 + argParts.length];
        cmd[0] = "sh";
        cmd[1] = scriptPath;
        System.arraycopy(argParts, 0, cmd, 2, argParts.length);
        return cmd;
    }

    /**
     * 分割参数字符串
     * 支持双引号内保留空格: 'arg1 "hello world" arg3' → ["arg1", "hello world", "arg3"]
     */
    private String[] splitArgs(String args) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }
}
