package com.example.agenttoolbox.tools;

import com.example.agenttoolbox.gm.MemoryEngine;
import org.json.JSONObject;

public class AttachProcessTool implements Tool {

    @Override
    public String getName() {
        return "gm_attach_process";
    }

    @Override
    public String getDescription() {
        return "附加到指定进程（需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            JSONObject properties = new JSONObject();
            properties.put("pid", new JSONObject().put("type", "integer").put("description", "进程ID"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new org.json.JSONArray().put("pid"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        int pid = arguments.getInt("pid");
        
        boolean success = MemoryEngine.attachProcess(pid);
        
        JSONObject result = new JSONObject();
        result.put("success", success);
        result.put("pid", pid);
        result.put("message", success ? "成功附加到进程 " + pid : "附加失败，请确保进程存在且已获取Root权限");
        
        return result.toString(2);
    }
}