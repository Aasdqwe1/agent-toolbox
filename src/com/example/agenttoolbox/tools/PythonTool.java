package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Python 脚本执行工具
 * 支持两种调用方式：
 *   1. 内联代码（包含换行、print、import 等），通过 python -c 执行
 *   2. 脚本文件路径，按路径执行
 * Android 上需安装 Python 环境（如 Termux、Chaquopy），否则会在诊断信息中提示。
 */
public class PythonTool implements Tool {

    @Override
    public String getName() {
        return "python";
    }

    @Override
    public String getDescription() {
        return "执行 Python 脚本或代码，支持内联代码和 .py 文件路径；Android 上需安装 Python（如 Termux），若未安装会在输出中提示找不到解释器";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject script = new JSONObject();
            script.put("type", "string");
            script.put("description", "要执行的 Python 代码（可多行）或脚本文件路径，例如: print('hello')、import os;print(os.listdir('/'))");
            properties.put("script", script);

            JSONObject args = new JSONObject();
            args.put("type", "string");
            args.put("description", "命令行参数（可选，仅文件模式）");
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
            // 查找 Python 解释器（以 sh 方式执行，避免 Java exec 直接找不到）
            String pythonCmd = findPythonCommand();

            boolean isInline = script.trim().contains("\n")
                    || script.trim().startsWith("print")
                    || script.trim().startsWith("import")
                    || script.trim().startsWith("from")
                    || script.trim().startsWith("#")
                    || script.trim().startsWith("def")
                    || script.trim().startsWith("class");

            // 使用 sh -c 方式包装，这样 -c 的 script 作为一个整体字符串被 sh 传给 python
            // 同时保证 pythonCmd 也能用 sh 解析到（which 查找）
            String shellCmd;
            if (isInline) {
                shellCmd = pythonCmd + " -c " + shellQuote(script);
            } else {
                shellCmd = pythonCmd + " " + shellQuote(script);
                if (!args.isEmpty()) {
                    shellCmd += " " + args;
                }
            }

            final String[] cmdArr = new String[] { "sh", "-c", shellCmd };

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
            result.append("执行方式: ").append(isInline ? "内联代码 (python -c)" : "脚本文件").append("\n");
            result.append("解释器: ").append(pythonCmd).append("\n");
            result.append("shell 命令: ").append(shellCmd).append("\n");
            result.append("退出码: ").append(exitValue).append("\n\n");

            if (output.length() > 0) {
                result.append("标准输出:\n").append(output.toString());
            }

            if (errorOutput.length() > 0) {
                result.append("标准错误:\n").append(errorOutput.toString());
            }

            // 失败时附加诊断提示
            if (exitValue != 0) {
                result.append("\n---- 诊断提示 ----\n");
                result.append("Python 执行失败（退出码=").append(exitValue).append("）。\n");
                result.append("建议排查方向:\n");
                result.append("  1. 确认 Python 是否安装:  shell which python3\n");
                result.append("  2. 查看 Python 版本:  shell python3 --version\n");
                result.append("  3. 测试最小脚本:  shell python3 -c \"print('ok')\"\n");
                result.append("  4. 检查错误输出中的 SyntaxError / ImportError / PermissionError\n");
                result.append("  5. 若 Termux/Chaquopy 未安装，可在 Android 上安装 Python 环境\n");
            }

            if (process.isAlive()) {
                process.destroy();
                result.append("\n执行超时（").append(timeout).append("秒），已被终止");
            }

            return result.toString();

        } catch (Exception e) {
            throw new Exception("执行 Python 失败: " + e.getMessage()
                + "；提示: 可先执行 shell which python3 确认解释器是否存在");
        }
    }

    /**
     * 探测可用的 Python 解释器
     */
    private String findPythonCommand() {
        String[] candidates = new String[] {
            "python3",
            "python",
            "python3.11",
            "python3.12",
            "/data/data/com.termux/files/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python",
            "/system/bin/python3",
            "/system/bin/python"
        };

        for (int i = 0; i < candidates.length; i++) {
            String cmd = candidates[i];
            try {
                Process test = Runtime.getRuntime().exec(
                    new String[] { "sh", "-c", "which " + cmd + " && " + cmd + " -c 'exit(0)'" });
                BufferedReader br = new BufferedReader(new InputStreamReader(test.getInputStream()));
                try {
                    String path = br.readLine();
                    test.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    int exit = test.exitValue();
                    if (path != null && !path.isEmpty() && exit == 0) {
                        return cmd;
                    }
                } finally {
                    br.close();
                }
            } catch (Exception e) {
                // continue
            }
        }

        // 找不到可用解释器时仍返回 python3，让后续执行能产出 "command not found" 错误信息
        return "python3";
    }

    /**
     * 按 POSIX shell 规则引用/转义字符串，
     * 用单引号包裹并转义内部单引号为 '\''
     */
    private static String shellQuote(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder();
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
