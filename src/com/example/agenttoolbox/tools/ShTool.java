package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * SH Shell 脚本执行工具
 */
public class ShTool implements Tool {

    @Override
    public String getName() {
        return "sh";
    }

    @Override
    public String getDescription() {
        return "执行 shell 脚本文件（.sh），支持相对路径和绝对路径";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject script = new JSONObject();
            script.put("type", "string");
            script.put("description", "脚本路径（.sh 文件），支持：1) 相对路径；2) /storage/emulated/0/... 完整路径；3) 直接传入脚本内容（以 #! 开头）");
            properties.put("script", script);

            JSONObject args = new JSONObject();
            args.put("type", "string");
            args.put("description", "传递给脚本的命令行参数（可选）");
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
            File scriptFile;
            boolean isInlineScript = script.trim().startsWith("#!");

            if (isInlineScript) {
                // 内联脚本：写入临时文件执行
                File tempDir = new File("/data/data/com.example.agenttoolbox/files");
                if (!tempDir.exists()) {
                    tempDir = new File("/storage/emulated/0/Android/data/com.example.agenttoolbox/files");
                }
                tempDir.mkdirs();
                scriptFile = new File(tempDir, "temp_script_" + System.currentTimeMillis() + ".sh");
                scriptFile.deleteOnExit();

                // 写入脚本内容
                java.io.FileOutputStream fos = new java.io.FileOutputStream(scriptFile);
                try {
                    fos.write(script.getBytes("UTF-8"));
                } finally {
                    fos.close();
                }
                scriptFile.setExecutable(true, false);
            } else {
                // 文件路径模式
                if (script.startsWith("/storage/") || script.startsWith("/sdcard")
                        || script.startsWith("/data/") || script.startsWith("/external")) {
                    scriptFile = new File(script);
                } else if (script.startsWith("/Download/") || script.startsWith("/Documents/")
                        || script.startsWith("/")) {
                    scriptFile = new File("/storage/emulated/0", script.substring(1));
                } else {
                    // 相对路径
                    scriptFile = new File("/data/data/com.example.agenttoolbox/files", script);
                }

                if (!scriptFile.exists()) {
                    // 尝试外部存储
                    File external = new File("/storage/emulated/0/Android/data/com.example.agenttoolbox/files", script);
                    if (external.exists()) {
                        scriptFile = external;
                    } else {
                        throw new Exception("脚本文件不存在: " + scriptFile.getAbsolutePath());
                    }
                }
            }

            if (!scriptFile.canRead()) {
                throw new Exception("脚本文件不可读，请检查权限");
            }

            // 构建命令
            String cmd = "sh " + scriptFile.getAbsolutePath();
            if (!args.isEmpty()) {
                cmd += " " + args;
            }

            final Process process = Runtime.getRuntime().exec("sh -c \"" + cmd.replace("\"", "\\\"") + "\"");

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
                    } catch ( Exception e) {
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
            result.append("脚本: ").append(isInlineScript ? "[内联脚本]" : scriptFile.getName()).append("\n");
            result.append("路径: ").append(scriptFile.getAbsolutePath()).append("\n");
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
            throw new Exception("执行 SH 脚本失败: " + e.getMessage());
        }
    }
}
