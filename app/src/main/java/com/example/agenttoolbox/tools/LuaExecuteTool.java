package com.example.agenttoolbox.tools;

import android.content.Context;
import com.example.agenttoolbox.gm.LuaEngine;
import org.json.JSONObject;

public class LuaExecuteTool implements Tool {

    private Context context;

    public LuaExecuteTool(Context context) {
        this.context = context;
        LuaEngine.setContext(context);
    }

    @Override
    public String getName() {
        return "gm_lua_execute";
    }

    @Override
    public String getDescription() {
        return "执行 Lua 脚本（支持 GG 修改器 API，需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            JSONObject properties = new JSONObject();
            properties.put("script", new JSONObject().put("type", "string").put("description", "Lua 脚本内容"));
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", new org.json.JSONArray().put("script"));
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String script = arguments.getString("script");
        
        String output = LuaEngine.executeScript(script);
        
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("output", output);
        
        return result.toString(2);
    }
}