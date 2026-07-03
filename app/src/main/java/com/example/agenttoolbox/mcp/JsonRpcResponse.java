package com.example.agenttoolbox.mcp;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON-RPC 2.0 响应对象
 */
public class JsonRpcResponse {
    
    private String jsonrpc = "2.0";
    private String id;
    private JSONObject result;
    private JSONObject error;
    
    /**
     * 创建成功响应
     */
    public static JsonRpcResponse success(String id, JSONObject result) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.id = id;
        resp.result = result;
        return resp;
    }
    
    /**
     * 创建错误响应
     */
    public static JsonRpcResponse error(String id, int code, String message, String data) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.id = id;
        try {
            resp.error = new JSONObject();
            resp.error.put("code", code);
            resp.error.put("message", message);
            if (data != null) {
                resp.error.put("data", data);
            }
        } catch (JSONException e) {
            // 正常情况下不会发生
            e.printStackTrace();
        }
        return resp;
    }
    
    /**
     * 解析错误
     */
    public static JsonRpcResponse parseError() {
        return error(null, -32700, "Parse error", "Invalid JSON was received by the server.");
    }
    
    /**
     * 请求无效
     */
    public static JsonRpcResponse invalidRequest(String id) {
        return error(id, -32600, "Invalid Request", "The JSON sent is not a valid Request object.");
    }
    
    /**
     * 方法不存在
     */
    public static JsonRpcResponse methodNotFound(String id) {
        return error(id, -32601, "Method not found", "The method does not exist / is not available.");
    }
    
    /**
     * 参数无效
     */
    public static JsonRpcResponse invalidParams(String id, String detail) {
        return error(id, -32602, "Invalid params", detail);
    }
    
    /**
     * 内部错误
     */
    public static JsonRpcResponse internalError(String id, String detail) {
        return error(id, -32603, "Internal error", detail);
    }
    
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("jsonrpc", jsonrpc);
        if (id != null) {
            obj.put("id", id);
        }
        if (result != null) {
            obj.put("result", result);
        }
        if (error != null) {
            obj.put("error", error);
        }
        return obj;
    }
    
    @Override
    public String toString() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }
    
}
