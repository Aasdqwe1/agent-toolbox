package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * CMD 命令执行工具
 * Android 上 /system/bin/cmd 是系统服务管理器，不是通用 shell，
 * 支持的子命令包括: cmd service, cmd activity, cmd package, cmd settings,
 *                   cmd dumpsys, cmd jobscheduler, cmd mount, cmd net, etc.
 * Windows 上才是 cmd.exe 命令行解释器。
 */
public class CmdTool implements Tool {

    @Override
    public String getName() {
        return "cmd";
    }

    @Override
    public String getDescription() {
        return "执行 Android 系统服务命令（/system/bin/cmd），如 dumpsys、settings、service、pm 等";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject command = new JSONObject();
            command.put("type", "string");
            command.put("description", "要执行的子命令，如: dumpsys meminfo、settings put system screen_brightness 100、service list、pm list packages、activity send");
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

        final StringBuilder output = new StringBuilder();
        final StringBuilder errorOutput = new StringBuilder();

        try {
            // Android /system/bin/cmd <subcommand>; Windows cmd.exe /c <command>
            String osName = System.getProperty("os.name", "").toLowerCase();
            String[] cmdArr;
            if (osName.contains("windows")) {
                cmdArr = new String[] { "cmd.exe", "/c", command };
            } else {
                // Android: 拆分为数组传入 exec，避免 sh -c 二次解析
                // 例如: cmd dumpsys meminfo -> ["cmd", "dumpsys", "meminfo"]
                cmdArr = buildArgs(command);
            }

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
            result.append("命令: ").append(command).append("\n");
            result.append("命令数组: ").append(java.util.Arrays.toString(cmdArr)).append("\n");
            result.append("退出码: ").append(exitValue).append("\n\n");

            if (output.length() > 0) {
                result.append("输出:\n").append(output.toString());
            }

            if (errorOutput.length() > 0) {
                result.append("错误:\n").append(errorOutput.toString());
            }

            if (process.isAlive()) {
                process.destroy();
                result.append("\n命令执行超时（").append(timeout).append("秒），已被终止");
            }

            return result.toString();

        } catch (Exception e) {
            throw new Exception("执行命令失败: " + e.getMessage());
        }
    }

    /**
     * 按空白字符拆分命令，但保留双/单引号内的内容，例如:
     *   "settings put system key 123" -> ["settings", "put", "system", "key", "123"]
     *   "dumpsys meminfo com.example.app" -> ["dumpsys", "meminfo", "com.example.app"]
     */
    private String[] buildArgs(String raw) {
        java.util.ArrayList tokens = new java.util.ArrayList();
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            char quote = 0;
            if (c == '"' || c == '\'') {
                quote = c;
                i++;
            }
            while (i < n) {
                char ch = raw.charAt(i);
                if (quote != 0) {
                    if (ch == quote) {
                        i++;
                        break;
                    }
                    sb.append(ch);
                    i++;
                } else {
                    if (ch == ' ' || ch == '\t') {
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
            }
            tokens.add(sb.toString());
        }
        String[] result = new String[tokens.size() + 1];
        result[0] = "cmd";
        for (int k = 0; k < tokens.size(); k++) {
            result[k + 1] = (String) tokens.get(k);
        }
        return result;
    }
}
