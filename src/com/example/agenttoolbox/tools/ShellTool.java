package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shell 命令执行工具
 * 使用 sh -c 解析任意 shell 命令，支持管道、重定向等。
 */
public class ShellTool implements Tool {

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return "在 Android 系统上执行 shell 命令，使用 sh -c 解析；常用命令: ls, cat, echo, rm, ps, top, df, du, grep, find, which, stat, mkdir, rmdir, chmod, chown, mv, cp, ln, touch, kill, pgrep, ifconfig, ip route 等";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject command = new JSONObject();
            command.put("type", "string");
            command.put("description", "要执行的 shell 命令，如 ls -la /sdcard、rm -f /data/local/tmp/x.txt、which rm、cat /proc/cpuinfo、ps -ef");
            properties.put("command", command);

            JSONObject timeout = new JSONObject();
            timeout.put("type", "integer");
            timeout.put("description", "命令超时时间（秒），默认 30 秒");
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

        // 安全限制：禁止某些危险命令
        String lowerCmd = command.toLowerCase();
        if (lowerCmd.contains("reboot") || lowerCmd.contains("shutdown")
                || lowerCmd.contains("mkfs") || lowerCmd.contains("dd if=")
                || lowerCmd.contains("> /dev/sd")) {
            throw new Exception("禁止执行危险命令: " + command);
        }

        final StringBuilder output = new StringBuilder();
        final StringBuilder errorOutput = new StringBuilder();

        try {
            // 关键点: 使用 String[] 方式传入，避免 Java exec 的字符串版再做一次空白切分
            // 这样 sh -c 的完整 command 会被作为单个参数传给 -c，
            // 真正的 shell 解析（引号、管道、重定向）交给系统 sh 来处理。
            String[] cmdArr = new String[] { "sh", "-c", command };

            final Process process = Runtime.getRuntime().exec(cmdArr);

            // 读取输出
            Thread stdoutThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        } finally {
                            reader.close();
                        }
                    } catch (Exception e) {
                        errorOutput.append("读取输出失败: ").append(e.getMessage()).append("\n");
                    }
                }
            });

            // 读取错误输出
            Thread stderrThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream()));
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errorOutput.append(line).append("\n");
                            }
                        } finally {
                            reader.close();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            });

            stdoutThread.start();
            stderrThread.start();

            // 等待命令执行完成
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeout * 1000L) {
                if (process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            int exitValue = process.exitValue();
            stdoutThread.join(1000);
            stderrThread.join(1000);

            StringBuilder result = new StringBuilder();
            result.append("执行命令: ").append(command).append("\n");
            result.append("命令数组: ").append(java.util.Arrays.toString(cmdArr)).append("\n");
            result.append("退出码: ").append(exitValue).append("\n\n");

            if (output.length() > 0) {
                result.append("标准输出:\n").append(output.toString());
            }

            if (errorOutput.length() > 0) {
                result.append("标准错误:\n").append(errorOutput.toString());
            }

            // 失败时附加诊断提示，帮助 LLM 做后续判断
            if (exitValue != 0) {
                result.append("\n---- 诊断提示 ----\n");
                result.append("命令执行失败（退出码=").append(exitValue).append("）。\n");
                result.append("建议排查方向（用 shell 工具继续执行以下命令之一）:\n");
                result.append("  1. 确认工具存在:  which <tool>   例如: which rm\n");
                result.append("  2. 查看工具类型:  file $(which rm)   或   ls -la /system/bin/rm\n");
                result.append("  3. 测试基本用法:  rm --help  或  rm -?\n");
                result.append("  4. 用 toybox/toybox 代替:  toybox rm -f /path/file\n");
                result.append("  5. 检查权限/SELinux:  stat <path>  或  getenforce\n");
                result.append("  6. 用重定向清空文件作为替代:  echo -n > /path/file  或  : > /path/file\n");
                result.append("  7. 列出目录确认路径:  ls -la <dir>\n");
            }

            if (process.isAlive()) {
                process.destroy();
                result.append("\n命令执行超时（").append(timeout).append("秒），已被终止");
            }

            return result.toString();

        } catch (Exception e) {
            throw new Exception("执行命令失败: " + e.getMessage()
                + "；提示: 可用 shell which <工具> 确认工具是否存在");
        }
    }
}
