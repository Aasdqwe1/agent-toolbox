package com.example.agenttoolbox.mcp;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON-RPC 2.0 请求对象
 */
public class JsonRpcRequest {
    
    private String jsonrpc;
    private String id;
    private String method;
    private JSONObject params;
    
    public JsonRpcRequest(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        this.jsonrpc = obj.optString("jsonrpc", "2.0");
        this.id = obj.optString("id", null);
        this.method = obj.optString("method", "");
        this.params = obj.optJSONObject("params");
        if (this.params == null) {
            this.params = new JSONObject();
        }
    }
    
    public String getJsonrpc() {
        return jsonrpc;
    }
    
    public String getId() {
        return id;
    }
    
    public String getMethod() {
        return method;
    }
    
    public JSONObject getParams() {
        return params;
    }
    
    public boolean isNotification() {
        return id == null || id.isEmpty();
    }
    
}
