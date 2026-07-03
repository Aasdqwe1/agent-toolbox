package com.example.agenttoolbox.tools;

import com.example.agenttoolbox.gm.MemoryEngine;
import org.json.JSONObject;

public class MemoryReadTool implements Tool {

    @Override
    public String getName() {
        return "gm_memory_read";
    }

    @Override
    public String getDescription() {
        return "读取指定内存地址的数值（需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            JSONObject properties = new JSONObject();
            properties.put("address", new JSONObject().put("type", "string").put("description", "内存地址（十六进制，如 0x7FFFFFFF）"));
            properties.put("type", new JSONObject().put("type", "string").put("description", "数据类型：byte/word/dword/qword/float/double，默认dword").put("default", "dword"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new org.json.JSONArray().put("address"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String addressStr = arguments.getString("address");
        String type = arguments.optString("type", "dword");
        
        if (MemoryEngine.getAttachedPid() == null) {
            return "{\"error\": \"请先使用 gm_attach_process 附加到进程\"}";
        }
        
        long address;
        try {
            if (addressStr.startsWith("0x")) {
                address = Long.parseLong(addressStr.substring(2), 16);
            } else {
                address = Long.parseLong(addressStr, 16);
            }
        } catch (NumberFormatException e) {
            return "{\"error\": \"无效的地址格式\"}";
        }
        
        Object value = MemoryEngine.readMemory(address, type);
        
        JSONObject result = new JSONObject();
        result.put("success", value != null);
        result.put("address", "0x" + Long.toHexString(address).toUpperCase());
        result.put("type", type);
        result.put("value", value != null ? value.toString() : "null");
        result.put("message", value != null ? "读取成功" : "读取失败");
        
        return result.toString(2);
    }
}