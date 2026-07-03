package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Python 脚本执行工具
 *
 * 所有 Python 执行都通过 PythonBridge，不再自己找解释器。
 * PythonBridge 自动处理：JNI 模式 > 进程模式 > 失败
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
                "自带内嵌 Python 环境，无需额外安装。";
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

        boolean isInline = isInlineCode(script);

        // 没有 Context 就无法使用 PythonBridge
        if (context == null) {
            throw new Exception("Python 工具未初始化（缺少 Context），请重启应用");
        }

        // 通过 PythonBridge 执行（自动选择 JNI 或进程模式）
        try {
            PythonBridge.init(context);
        } catch (Exception e) {
            throw new Exception("Python 初始化失败: " + e.getMessage());
        }

        String code;
        if (isInline) {
            code = script;
        } else {
            // 读取脚本文件内容
            java.io.File file = FilePathResolver.resolveForRead(script);
            if (!file.exists()) {
                throw new Exception("脚本文件不存在: " + file.getAbsolutePath());
            }
            code = readFileContent(file);
        }

        String result = PythonBridge.exec(code);

        StringBuilder sb = new StringBuilder();
        sb.append("模式: ").append(PythonBridge.getStatus()).append("\n");
        sb.append("类型: ").append(isInline ? "内联代码" : "脚本文件").append("\n\n");
        sb.append(result);
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
                || s.startsWith("while ");
    }

    private String readFileContent(java.io.File file) throws Exception {
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
}
