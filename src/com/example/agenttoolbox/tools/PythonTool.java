package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Python 脚本执行工具
 */
public class PythonTool implements Tool {

    @Override
    public String getName() {
        return "python";
    }

    @Override
    public String getDescription() {
        return "在 Android 上执行 Python 脚本（需安装 Python 环境，如 Termux）";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject script = new JSONObject();
            script.put("type", "string");
            script.put("description", "要执行的 Python 代码或脚本路径");
            properties.put("script", script);

            JSONObject args = new JSONObject();
            args.put("type", "string");
            args.put("description", "命令行参数（可选）");
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

        final StringBuilder output = new StringBuilder();
        final StringBuilder errorOutput = new StringBuilder();

        try {
            // 查找 Python 解释器
            String pythonCmd = findPythonCommand();

            ProcessBuilder pb;
            if (script.contains("\n") || script.startsWith("print") || script.startsWith("import")) {
                // 内联代码模式，使用 python -c
                pb = new ProcessBuilder(pythonCmd, "-c", script);
            } else {
                // 文件模式
                String fullCmd = pythonCmd + " \"" + script + "\"";
                if (!args.isEmpty()) {
                    fullCmd += " " + args;
                }
                pb = new ProcessBuilder("sh", "-c", fullCmd);
            }

            pb.redirectErrorStream(false);
            final Process process = pb.start();

            // 读取输出
            Thread stdoutThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
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
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
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

            // 等待执行完成
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
            result.append("Python ");
            if (script.contains("\n")) {
                result.append("脚本执行完毕");
            } else {
                result.append("文件: ").append(script);
            }
            result.append("\n解释器: ").append(pythonCmd).append("\n");
            result.append("退出码: ").append(exitValue).append("\n\n");

            if (output.length() > 0) {
                result.append("输出:\n").append(output.toString());
            }

            if (errorOutput.length() > 0) {
                result.append("错误:\n").append(errorOutput.toString());
            }

            if (process.isAlive()) {
                process.destroy();
                result.append("\n执行超时（").append(timeout).append("秒），已被终止");
            }

            return result.toString();

        } catch (Exception e) {
            throw new Exception("执行 Python 失败: " + e.getMessage());
        }
    }

    private String findPythonCommand() {
        String[] candidates = {
            "python3",
            "python",
            "/data/data/com.termux/files/usr/bin/python",
            "/system/bin/python3",
            "/system/bin/python"
        };

        for (final String cmd : candidates) {
            try {
                Process test = Runtime.getRuntime().exec("sh -c \"which " + cmd + "\"");
                BufferedReader br = new BufferedReader(new InputStreamReader(test.getInputStream()));
                try {
                    String path = br.readLine();
                    test.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (path != null && !path.isEmpty()) {
                        return cmd;
                    }
                } finally {
                    br.close();
                }
            } catch (Exception e) {
                // continue
            }
        }

        return "python3";
    }
}
