package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.agenttoolbox.skills.SkillManager;

/**
 * skill_read 工具 —— 渐进式披露技能知识。
 * 参数：{ skill_id: 必填, reference: 可选（references 下的文件名） }
 *   - 不传 reference：返回该技能 SKILL.md 正文
 *   - 传 reference：返回 references/<reference> 文件内容
 */
public class SkillReadTool implements Tool {

    @Override
    public String getName() {
        return "skill_read";
    }

    @Override
    public String getDescription() {
        return "读取已加载技能的知识。参数 skill_id 指定技能；可选 reference 指定 references/ 下文件名，" +
                "不传则返回该技能 SKILL.md 正文。用于按需获取领域知识，避免一次性灌入系统提示。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject skillId = new JSONObject();
            skillId.put("type", "string");
            skillId.put("description", "技能 ID（来自 skills/list）");
            properties.put("skill_id", skillId);

            JSONObject reference = new JSONObject();
            reference.put("type", "string");
            reference.put("description", "可选，references/ 下的文件名，如 conventions.md");
            properties.put("reference", reference);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("skill_id");
            schema.put("required", required);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        if (arguments == null || !arguments.has("skill_id")) {
            throw new Exception("缺少参数 skill_id");
        }
        String skillId = arguments.getString("skill_id");
        String reference = arguments.has("reference") ? arguments.getString("reference") : "";
        return SkillManager.getInstance().readSkill(skillId, reference);
    }
}
