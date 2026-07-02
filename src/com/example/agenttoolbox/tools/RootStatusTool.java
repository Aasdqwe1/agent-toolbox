package com.example.agenttoolbox.tools;

import com.example.agenttoolbox.gm.RootManager;
import org.json.JSONObject;

public class RootStatusTool implements Tool {

    @Override
    public String getName() {
        return "gm_root_status";
    }

    @Override
    public String getDescription() {
        return "检查设备的 Root 权限状态";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        boolean hasRoot = RootManager.checkRootAccess();
        String status = RootManager.getRootStatus();
        
        JSONObject result = new JSONObject();
        result.put("hasRoot", hasRoot);
        result.put("status", status);
        
        return result.toString(2);
    }
}