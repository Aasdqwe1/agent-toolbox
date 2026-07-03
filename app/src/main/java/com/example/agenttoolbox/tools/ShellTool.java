package com.example.agenttoolbox.tools;

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

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return "在 Android 系统上执行 shell 命令（sh -c），支持管道、重定向等。" +
                "常用命令: ls, cat, echo, ps, grep, find, stat, mkdir, mv, cp, touch, kill 等";
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
}
