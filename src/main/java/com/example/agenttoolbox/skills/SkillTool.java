package com.example.agenttoolbox.skills;

import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import com.example.agenttoolbox.tools.Tool;
import com.example.agenttoolbox.tools.PythonBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Skill 工具包装 —— 实现 Tool 接口，把 skill 定义的工具接入 ToolManager，
 * 执行时复用 PythonBridge（与 PythonTool 同源通道）。
 *
 * 参数以 base64(JSON) 形式注入为 Python 变量 __ARGS__，供脚本读取：
 *   import json
 *   print(__ARGS__["text"])
 */
public class SkillTool implements Tool {

    private final String skillId;
    private final String name;
    private final String description;
    private final JSONObject inputSchema;
    private final String execType;   // "script" | "inline"
    private final String execSrc;    // 脚本相对 skill 目录的路径（script 类型）
    private final String code;       // 内联代码 / 已解析的脚本内容
    private final File skillDir;     // 运行时 skill 目录（script 类型读取用）
    private final boolean fromAssets;
    private final Context context;

    public SkillTool(String skillId, String name, String description, JSONObject inputSchema,
                     String execType, String execSrc, String code, File skillDir,
                     boolean fromAssets, Context context) {
        this.skillId = skillId;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.execType = execType;
        this.execSrc = execSrc;
        this.code = code;
        this.skillDir = skillDir;
        this.fromAssets = fromAssets;
        this.context = context;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public JSONObject getInputSchema() {
        return inputSchema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        // 1) 解析要执行的代码
        String source;
        if ("inline".equals(execType)) {
            source = (code != null) ? code : "";
        } else {
            // script 类型：execSrc 相对于 skill 目录（仅运行时 skill 走此路）
            if (fromAssets || skillDir == null) {
                // assets 内置脚本应在注册时已解析为 inline code
                source = (code != null) ? code : "";
            } else {
                File f = new File(skillDir, execSrc);
                if (!f.exists()) throw new Exception("技能脚本不存在: " + f.getAbsolutePath());
                source = readFile(f);
            }
        }
        if (source == null || source.trim().isEmpty()) {
            throw new Exception("技能工具无可执行内容: " + name);
        }

        // 2) 把入参以 base64(JSON) 注入为 __ARGS__（避免引号/转义问题）
        String argsJson = (arguments != null) ? arguments.toString() : "{}";
        String b64 = Base64.encodeToString(argsJson.getBytes("UTF-8"), Base64.NO_WRAP);
        String header = "import base64, json\n"
                + "__ARGS__ = json.loads(base64.b64decode('" + b64 + "').decode('utf-8'))\n";
        String fullCode = header + source;

        // 3) 经 PythonBridge 执行
        try {
            PythonBridge.init(context);
        } catch (Exception e) {
            throw new Exception("Python 初始化失败: " + e.getMessage());
        }
        return PythonBridge.exec(fullCode);
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
