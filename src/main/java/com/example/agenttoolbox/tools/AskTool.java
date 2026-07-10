package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Ask 工具 —— 向用户提问并等待回答。
 *
 * 当 AI 需要用户确认、提供信息或选择时调用此工具。
 * 工具返回格式化的问题，用户看到后在下一条消息中回答。
 */
public class AskTool implements Tool {

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public String getDescription() {
        return "向用户提问并等待用户回答。参数 question 为问题内容（必填），可选的 options 为选择项数组。AI 需要用户确认、选择或补充信息时调用此工具";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject question = new JSONObject();
            question.put("type", "string");
            question.put("description", "要向用户提出的问题");
            properties.put("question", question);

            JSONObject options = new JSONObject();
            options.put("type", "array");
            options.put("description", "可选的选项列表，供用户选择");
            JSONObject item = new JSONObject();
            item.put("type", "string");
            options.put("items", item);
            properties.put("options", options);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("question");
            schema.put("required", required);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        if (arguments == null || !arguments.has("question")) {
            throw new Exception("缺少必填参数: question");
        }

        String question = arguments.getString("question");
        StringBuilder sb = new StringBuilder();
        sb.append("❓ ").append(question).append("\n\n");

        // 如果有选项，格式化列出
        if (arguments.has("options")) {
            JSONArray opts = arguments.optJSONArray("options");
            if (opts != null && opts.length() > 0) {
                sb.append("可选回答：\n");
                for (int i = 0; i < opts.length(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(opts.optString(i, "")).append("\n");
                }
                sb.append("\n请选择一个选项回答，或直接输入你的回答。");
            }
        } else {
            sb.append("请回答以上问题。");
        }

        return sb.toString();
    }
}
