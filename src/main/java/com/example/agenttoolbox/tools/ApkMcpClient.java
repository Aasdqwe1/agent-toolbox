package com.example.agenttoolbox.tools;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * APK MCP 客户端 — 连接 MT 管理器的本地 MCP 服务
 * 负责 MCP 握手、工具列表拉取、工具调用转发
 */
public class ApkMcpClient {

    private static final String TAG = "ApkMcpClient";
    private static ApkMcpClient instance;

    private String mcpUrl = "http://127.0.0.1:8787/mcp";
    private boolean connected = false;
    private boolean enabled = true; // 默认启用，MT 未运行时会静默跳过
    private JSONArray remoteTools = new JSONArray();
    private int requestId = 0;

    private ApkMcpClient() {}

    public static synchronized ApkMcpClient getInstance() {
        if (instance == null) instance = new ApkMcpClient();
        return instance;
    }

    public void setUrl(String url) {
        this.mcpUrl = url;
        this.connected = false;
    }

    public String getUrl() {
        return mcpUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.connected = false;
            this.remoteTools = new JSONArray();
        }
    }

    /**
     * 连接 MT APK MCP，执行 MCP 握手并拉取工具列表
     */
    public boolean connect() {
        if (!enabled) return false;
        if (connected) return true;

        try {
            Log.i(TAG, "正在连接 MT APK MCP: " + mcpUrl);

            // 1. initialize
            JSONObject initParams = new JSONObject();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.put("capabilities", new JSONObject());
            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", "AgentToolbox");
            clientInfo.put("version", "1.0.0");
            initParams.put("clientInfo", clientInfo);

            JSONObject initResp = sendMcpRequest("initialize", initParams);
            if (initResp == null) {
                Log.w(TAG, "initialize 失败");
                return false;
            }
            Log.i(TAG, "initialize 成功: " + initResp.toString().substring(0, Math.min(200, initResp.toString().length())));

            // 2. 从 initialize 响应中提取工具（MT 管理器工具名自带 mt_apk_ 前缀）
            JSONArray tools = new JSONArray();
            try {
                JSONObject result = initResp.optJSONObject("result");
                if (result != null) {
                    JSONObject caps = result.optJSONObject("capabilities");
                    if (caps != null) {
                        JSONObject capsTools = caps.optJSONObject("tools");
                        if (capsTools != null) {
                            JSONObject available = capsTools.optJSONObject("availableTools");
                            if (available != null) {
                                java.util.Iterator<String> keys = available.keys();
                                while (keys.hasNext()) {
                                    String name = keys.next();
                                    JSONObject info = available.optJSONObject(name);
                                    JSONObject tool = new JSONObject();
                                    tool.put("name", name);  // MT 工具名已自带 mt_apk_ 前缀
                                    tool.put("description", name);
                                    tool.put("inputSchema", new JSONObject());
                                    tools.put(tool);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "从 initialize 提取工具失败: " + e.getMessage());
            }

            if (tools.length() == 0) {
                Log.w(TAG, "initialize 中未找到 APK 工具");
                return false;
            }

            remoteTools = tools;
            connected = true;
            Log.i(TAG, "连接成功，获取到 " + tools.length() + " 个 APK 工具");

            // 3. initialized notification
            sendMcpNotification("notifications/initialized", new JSONObject());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "连接失败: " + e.getMessage());
            connected = false;
            return false;
        }
    }

    /**
     * 获取远程工具列表（已缓存）
     */
    public JSONArray getRemoteTools() {
        return remoteTools;
    }

    /**
     * 调用 MT 的 APK 工具
     */
    public JSONObject callTool(String toolName, JSONObject arguments) {
        if (!connected && !connect()) {
            return errorResult("APK MCP 未连接，请确保 MT 管理器已启动 APK MCP 服务");
        }

        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);  // 工具名已自带 mt_apk_ 前缀，直接传给 MT
            params.put("arguments", arguments != null ? arguments : new JSONObject());

            JSONObject resp = sendMcpRequest("tools/call", params);
            if (resp == null) {
                return errorResult("调用 " + toolName + " 失败：无响应");
            }

            // MCP tools/call 返回格式：result.content[].text
            JSONObject result = resp.optJSONObject("result");
            if (result == null) {
                // 可能在顶层直接有 content
                JSONArray content = resp.optJSONArray("content");
                if (content != null) {
                    return wrapContent(content);
                }
                return errorResult("调用 " + toolName + " 返回格式异常");
            }

            JSONArray content = result.optJSONArray("content");
            if (content != null) {
                return wrapContent(content);
            }

            // 有些工具直接返回 result 对象
            JSONObject resultObj = new JSONObject();
            resultObj.put("isError", false);
            JSONArray contentArr = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", result.toString());
            contentArr.put(item);
            resultObj.put("content", contentArr);
            return resultObj;

        } catch (Exception e) {
            Log.e(TAG, "callTool 失败: " + toolName + " - " + e.getMessage());
            return errorResult("调用 " + toolName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 发送 MCP 请求（JSON-RPC 2.0）
     */
    private JSONObject sendMcpRequest(String method, JSONObject params) {
        HttpURLConnection conn = null;
        try {
            int id = ++requestId;
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", method);
            rpc.put("params", params);
            rpc.put("id", id);

            String body = rpc.toString();
            Log.d(TAG, "请求 [" + id + "]: " + method + " (长度=" + body.length() + ")");

            URL url = new URL(mcpUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + method);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String respStr = sb.toString();
            if (respStr.isEmpty()) return null;

            JSONObject resp = new JSONObject(respStr);

            // 检查错误
            if (resp.has("error")) {
                JSONObject error = resp.optJSONObject("error");
                String errMsg = error != null ? error.optString("message", "未知错误") : "未知错误";
                Log.w(TAG, method + " 返回错误: " + errMsg);
                return null;
            }

            return resp;

        } catch (Exception e) {
            Log.e(TAG, "sendMcpRequest " + method + " 异常: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 发送 MCP 通知（无 id，不需要响应）
     */
    private void sendMcpNotification(String method, JSONObject params) {
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", method);
            rpc.put("params", params);
            // 通知无 id

            URL url = new URL(mcpUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);

            OutputStream os = conn.getOutputStream();
            os.write(rpc.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            conn.getResponseCode(); // 忽略响应
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "sendMcpNotification 失败: " + e.getMessage());
        }
    }

    private JSONObject wrapContent(JSONArray content) {
        JSONObject result = new JSONObject();
        try {
            result.put("isError", false);
            result.put("content", content);
        } catch (JSONException e) {
            // ignore
        }
        return result;
    }

    private JSONObject errorResult(String message) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", "text");
            item.put("text", message);
            content.put(item);
            result.put("isError", true);
            result.put("content", content);
        } catch (JSONException e) {
            // ignore
        }
        return result;
    }
}