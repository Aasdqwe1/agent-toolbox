package com.example.agenttoolbox.tools;

import com.example.agenttoolbox.gm.MemoryEngine;
import org.json.JSONArray;
import org.json.JSONObject;

public class AobSearchTool implements Tool {

    @Override
    public String getName() {
        return "gm_aob_search";
    }

    @Override
    public String getDescription() {
        return "搜索指定的内存特征码（AOB）或地址（需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            JSONObject properties = new JSONObject();
            properties.put("pattern", new JSONObject().put("type", "string").put("description", "特征码模式（如 48 8B 05 ?? ?? ?? ??）或内存地址（如 0x7FFFFFFF）"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new org.json.JSONArray().put("pattern"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String pattern = arguments.getString("pattern");
        
        if (MemoryEngine.getAttachedPid() == null) {
            return "{\"error\": \"请先使用 gm_attach_process 附加到进程\"}";
        }
        
        JSONArray results = MemoryEngine.searchAob(pattern);
        
        JSONObject result = new JSONObject();
        result.put("count", results.length());
        result.put("results", results);
        
        return result.toString(2);
    }
}