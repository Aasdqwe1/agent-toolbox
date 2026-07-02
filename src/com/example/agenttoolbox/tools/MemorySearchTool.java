package com.example.agenttoolbox.tools;

import com.example.agenttoolbox.gm.MemoryEngine;
import org.json.JSONArray;
import org.json.JSONObject;

public class MemorySearchTool implements Tool {

    @Override
    public String getName() {
        return "gm_memory_search";
    }

    @Override
    public String getDescription() {
        return "在已附加的进程中搜索指定数值（需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            JSONObject properties = new JSONObject();
            properties.put("value", new JSONObject().put("type", "string").put("description", "要搜索的数值"));
            properties.put("type", new JSONObject().put("type", "string").put("description", "数据类型：byte/word/dword/qword/float/double，默认dword").put("default", "dword"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new org.json.JSONArray().put("value"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String value = arguments.getString("value");
        String type = arguments.optString("type", "dword");
        
        if (MemoryEngine.getAttachedPid() == null) {
            return "{\"error\": \"请先使用 gm_attach_process 附加到进程\"}";
        }
        
        Object numValue;
        try {
            if ("float".equals(type) || "double".equals(type)) {
                numValue = Double.parseDouble(value);
            } else {
                numValue = Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            return "{\"error\": \"无效的数值格式\"}";
        }
        
        JSONArray results = MemoryEngine.searchExact(numValue, type);
        
        JSONObject result = new JSONObject();
        result.put("count", results.length());
        result.put("results", results);
        
        return result.toString(2);
    }
}