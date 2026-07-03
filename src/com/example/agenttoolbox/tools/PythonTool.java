package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Python 脚本执行工具 - 最终版
 *
 * 优先级：
 * 1. JNI 嵌入模式（libpython3.14.so，无需外部进程）
 * 2. 内嵌 Python 二进制（从 assets 解压）
 * 3. 系统 Python（Termux 等）
 */
public class PythonTool implements Tool {

    private Context context;

    public PythonTool() {}

    public PythonTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "python";
    }

    @Override
    public String getDescription() {
        return "执行 Python 脚本或代码，支持内联代码和 .py 文件路径。" +
                "自带内嵌 Python 3.14 环境，无需额外安装。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject script = new JSONObject();
            script.put("type", "string");
            script.put("description", "要执行的 Python 代码（可多行）或脚本文件路径");
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

        // 判断是内联代码还是文件路径
        boolean isInline = isInlineCode(script);

        // ===== 优先级 1: JNI 嵌入模式 =====
        if (context != null && PythonBridge.isAvailable()) {
            try {
                PythonBridge.init(context);
                String code = isInline ? script : readFileToCode(script, args);
                String result = PythonBridge.exec(code);

                StringBuilder sb = new StringBuilder();
                sb.append("执行方式: JNI 嵌入 (libpython3.14)\n");
                sb.append("模式: ").append(isInline ? "内联代码" : "脚本文件").append("\n\n");
                sb.append(result);
                return sb.toString();
            } catch (Exception e) {
                // JNI 失败，降级
            }
        }

        // ===== 优先级 2/3: 进程模式 =====
        return executeViaProcess(script, args, timeout, isInline);
    }

    /**
     * 进程模式执行（回退方案）
     */
    private String executeViaProcess(String script, String args, int timeout,
                                      boolean isInline) throws Exception {
        // 查找 Python 解释器
        String pythonCmd = findPythonCommand();

        String shellCmd;
        if (isInline) {
            shellCmd = pythonCmd + " -c " + shellQuote(script);
        } else {
            shellCmd = pythonCmd + " " + shellQuote(script);
            if (!args.isEmpty()) {
                shellCmd += " " + args;
            }
        }

        String[] cmdArr = {"sh", "-c", shellCmd};
        ProcessRunner.Result result = ProcessRunner.exec(cmdArr, timeout);

        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(shellCmd).append("\n");
        sb.append("执行方式: 进程模式 (").append(pythonCmd).append(")\n");
        sb.append("退出码: ").append(result.exitCode).append("\n");

        if (result.timedOut) {
            sb.append("⚠️ 超时（").append(timeout).append("秒），已被强制终止\n");
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
        if (result.exitCode != 0 && !result.timedOut) {
            sb.append("\n提示: 检查语法、import、文件路径是否正确");
        }

        return sb.toString();
    }

    /**
     * 将脚本文件读取为可执行代码
     */
    private String readFileToCode(String path, String args) throws Exception {
        File file = FilePathResolver.resolveForRead(path);
        if (!file.exists()) throw new Exception("脚本文件不存在: " + file.getAbsolutePath());

        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file)));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        return sb.toString();
    }

    private boolean isInlineCode(String script) {
        String s = script.trim();
        return s.contains("\n")
                || s.startsWith("print")
                || s.startsWith("import")
                || s.startsWith("from")
                || s.startsWith("#")
                || s.startsWith("def ")
                || s.startsWith("class ")
                || s.startsWith("for ")
                || s.startsWith("if ")
                || s.startsWith("while ")
                || s.startsWith("x=")
                || s.startsWith("x =");
    }

    private String findPythonCommand() {
        String[] candidates = {
            "python3", "python",
            "python3.12", "python3.11", "python3.10",
            "/data/data/com.termux/files/usr/bin/python3",
        };
        for (String cmd : candidates) {
            try {
                ProcessRunner.Result r = ProcessRunner.exec(
                    new String[]{"sh", "-c", "which " + cmd}, 3);
                if (r.exitCode == 0 && !r.stdout.trim().isEmpty()) return cmd;
            } catch (Exception ignored) {}
        }
        return "python3";
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') sb.append("'\\''");
            else sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }
}
