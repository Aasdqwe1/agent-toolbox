package com.example.agenttoolbox.mcp;

import android.content.Context;
import com.example.agenttoolbox.DeepSeekChatBridge;
import com.example.agenttoolbox.tools.ToolManager;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * MCP 服务端 - 基于HTTP的JSON-RPC 2.0服务 + 静态网页服务
 */
public class McpServer {
    
    private int port;
    private ServerSocket serverSocket;
    private boolean running = false;
    private Thread serverThread;
    
    // 静态变量，记录是否有服务在运行
    private static boolean serverRunning = false;
    
    /**
     * 检查 MCP 服务是否在运行（静态方法）
     */
    public static boolean isServiceRunning() {
        return serverRunning;
    }
    private OnLogListener logListener;
    private Context context;
    
    public interface OnLogListener {
        void onLog(String message);
    }
    
    public McpServer(int port, Context context) {
        this.port = port;
        this.context = context;
    }
    
    public void setOnLogListener(OnLogListener listener) {
        this.logListener = listener;
    }
    
    private void log(String message) {
        if (logListener != null) {
            logListener.onLog(message);
        }
    }
    
    /**
     * 启动服务
     */
    public void start() throws IOException {
        if (running) {
            return;
        }
        
        serverSocket = new ServerSocket(port);
        running = true;
        serverRunning = true;
        
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                log("MCP服务已启动，监听端口: " + port);
                log("本机IP地址: " + getLocalIpAddress());
                log("浏览器访问: http://" + getLocalIpAddress() + ":" + port);
                
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        new Thread(new ClientHandler(clientSocket)).start();
                    } catch (IOException e) {
                        if (running) {
                            log("接受连接失败: " + e.getMessage());
                        }
                    }
                }
            }
        });
        serverThread.start();
    }
    
    /**
     * 停止服务
     */
    public void stop() {
        running = false;
        serverRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log("关闭服务失败: " + e.getMessage());
        }
        log("MCP服务已停止");
    }
    
    /**
     * 获取本机IP地址
     */
    public String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') == -1) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public int getPort() {
        return port;
    }
    
    /**
     * 读取assets文件内容
     */
    private String readAssetFile(String fileName) {
        try {
            InputStream is = context.getAssets().open(fileName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            is.close();
            return sb.toString();
        } catch (IOException e) {
            log("读取文件失败: " + fileName + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 客户端处理线程
     */
    private class ClientHandler implements Runnable {
        
        private Socket clientSocket;
        
        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }
        
        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
                OutputStream out = clientSocket.getOutputStream();
                
                // 读取HTTP请求行
                String requestLine = in.readLine();
                if (requestLine == null) {
                    clientSocket.close();
                    return;
                }
                
                // 解析请求方法和路径
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";
                
                // 读取请求头
                StringBuilder headersBuilder = new StringBuilder();
                String line;
                int contentLength = 0;
                
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    headersBuilder.append(line).append("\n");
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }
                
                // 根据请求方法处理
                if ("GET".equalsIgnoreCase(method)) {
                    handleGetRequest(path, out);
                } else if ("POST".equalsIgnoreCase(method)) {
                    // 读取请求体
                    char[] body = new char[contentLength];
                    in.read(body, 0, contentLength);
                    String requestBody = new String(body);
                    handlePostRequest(path, requestBody, out);
                } else if ("OPTIONS".equalsIgnoreCase(method)) {
                    handleOptionsRequest(out);
                } else {
                    sendErrorResponse(out, 405, "Method Not Allowed");
                }
                
                out.flush();
                in.close();
                out.close();
                clientSocket.close();
                
            } catch (Exception e) {
                log("处理客户端请求失败: " + e.getMessage());
                try {
                    clientSocket.close();
                } catch (IOException ex) {
                    // ignore
                }
            }
        }
        
        /**
         * 处理GET请求 - 返回静态网页
         */
        private void handleGetRequest(String path, OutputStream out) throws IOException {
            log("GET请求: " + path);
            
            String fileName;
            String contentType;
            
            if ("/".equals(path) || path.isEmpty()) {
                fileName = "test_client.html";
                contentType = "text/html; charset=UTF-8";
            } else {
                // 去掉开头的斜杠
                fileName = path.startsWith("/") ? path.substring(1) : path;
                // 简单的MIME类型判断
                if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                    contentType = "text/html; charset=UTF-8";
                } else if (fileName.endsWith(".css")) {
                    contentType = "text/css; charset=UTF-8";
                } else if (fileName.endsWith(".js")) {
                    contentType = "application/javascript; charset=UTF-8";
                } else if (fileName.endsWith(".json")) {
                    contentType = "application/json; charset=UTF-8";
                } else {
                    contentType = "application/octet-stream";
                }
            }
            
            String content = readAssetFile(fileName);
            
            if (content != null) {
                byte[] contentBytes = content.getBytes("UTF-8");
                String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + contentBytes.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n";
                out.write(response.getBytes("UTF-8"));
                out.write(contentBytes);
                log("返回页面: " + fileName + " (" + contentBytes.length + " 字节)");
            } else {
                sendErrorResponse(out, 404, "Not Found");
            }
        }
        
        /**
         * 处理POST请求 - JSON-RPC + DeepSeek 聊天
         */
        private void handlePostRequest(String path, String requestBody, OutputStream out) throws IOException {
            log("收到请求: " + requestBody);

            // DeepSeek 聊天接口
            if (path.startsWith("/api/chat/")) {
                handleChatRequest(path, requestBody, out);
                return;
            }

            // 处理JSON-RPC请求
            String responseBody = handleJsonRpcRequest(requestBody);

            log("返回响应: " + responseBody);

            // 发送HTTP响应
            String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + responseBody.getBytes("UTF-8").length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "\r\n" +
                responseBody;

            out.write(response.getBytes("UTF-8"));
        }

        /**
         * 处理 DeepSeek 聊天请求
         */
        private void handleChatRequest(String path, String requestBody, OutputStream out) throws IOException {
            String responseBody;
            try {
                org.json.JSONObject body = requestBody != null && requestBody.length() > 2
                    ? new org.json.JSONObject(requestBody) : new org.json.JSONObject();
                String action = path;
                // 允许末尾斜杠
                if (action.endsWith("/")) action = action.substring(0, action.length() - 1);

                DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();

                if ("/api/chat/sessions".equals(action)) {
                    // 获取会话列表
                    if (!bridge.isRegistered()) {
                        responseBody = new org.json.JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接，请先打开 DeepSeek 页面")
                            .toString();
                    } else {
                        log("DeepSeek 会话列表查询");
                        String sessionsJson = bridge.getSessions();
                        if (sessionsJson == null) {
                            responseBody = new org.json.JSONObject()
                                .put("success", false)
                                .put("error", "提取会话列表失败")
                                .toString();
                        } else {
                            // sessionsJson 本身就是完整 JSON，直接包一层 success
                            responseBody = new org.json.JSONObject()
                                .put("success", true)
                                .put("data", new org.json.JSONObject(sessionsJson))
                                .toString();
                        }
                    }
                } else if ("/api/chat/select".equals(action)) {
                    // 切换会话
                    String sessionId = body.optString("session_id", "").trim();
                    if (sessionId.isEmpty()) {
                        responseBody = new org.json.JSONObject()
                            .put("success", false)
                            .put("error", "session_id 参数不能为空")
                            .toString();
                    } else if (!bridge.isRegistered()) {
                        responseBody = new org.json.JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接")
                            .toString();
                    } else {
                        log("DeepSeek 切换会话: " + sessionId);
                        boolean ok = bridge.selectSession(sessionId);
                        responseBody = new org.json.JSONObject()
                            .put("success", ok)
                            .put("message", ok ? "已切换会话 " + sessionId : "未找到会话 " + sessionId)
                            .put("session_id", sessionId)
                            .toString();
                    }
                } else if ("/api/chat/send".equals(action)) {
                    // 发送消息并等待回复
                    String message = body.optString("message", "").trim();
                    if (message.isEmpty()) {
                        responseBody = new org.json.JSONObject()
                            .put("success", false)
                            .put("error", "message 参数不能为空")
                            .toString();
                    } else {
                        // 检查 DeepSeekActivity 是否已注册 WebView
                        if (!com.example.agenttoolbox.DeepSeekChatBridge.getInstance().isRegistered()) {
                            responseBody = new org.json.JSONObject()
                                .put("success", false)
                                .put("error", "DeepSeek 未连接，请先打开 DeepSeek 页面并确保已登录")
                                .toString();
                        } else {
                            log("DeepSeek 聊天请求: " + message.substring(0, Math.min(50, message.length())));
                            String reply = com.example.agenttoolbox.DeepSeekChatBridge.getInstance().sendMessage(message);
                            if (reply != null) {
                                responseBody = new org.json.JSONObject()
                                    .put("success", true)
                                    .put("reply", reply)
                                    .toString();
                            } else {
                                responseBody = new org.json.JSONObject()
                                    .put("success", false)
                                    .put("error", "未收到回复（可能超时或页面未响应）")
                                    .toString();
                            }
                        }
                    }
                } else if ("/api/chat/status".equals(action)) {
                    boolean registered = com.example.agenttoolbox.DeepSeekChatBridge.getInstance().isRegistered();
                    responseBody = new org.json.JSONObject()
                        .put("success", true)
                        .put("connected", registered)
                        .put("message", registered ? "DeepSeek 已连接" : "DeepSeek 未连接")
                        .toString();
                } else {
                    responseBody = new org.json.JSONObject()
                        .put("success", false)
                        .put("error", "未知聊天接口: " + action)
                        .toString();
                }
            } catch (Exception e) {
                log("聊天请求处理异常: " + e.getMessage());
                responseBody = new org.json.JSONObject()
                    .put("success", false)
                    .put("error", e.getMessage())
                    .toString();
            }

            log("聊天响应: " + (responseBody.length() > 100 ? responseBody.substring(0, 100) + "..." : responseBody));

            String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + responseBody.getBytes("UTF-8").length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "\r\n" +
                responseBody;

            out.write(response.getBytes("UTF-8"));
        }
        
        /**
         * 处理OPTIONS请求 - CORS预检
         */
        private void handleOptionsRequest(OutputStream out) throws IOException {
            String response = "HTTP/1.1 200 OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";
            out.write(response.getBytes("UTF-8"));
        }
        
        /**
         * 发送错误响应
         */
        private void sendErrorResponse(OutputStream out, int code, String message) throws IOException {
            String body = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
            String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + body.getBytes("UTF-8").length + "\r\n" +
                "\r\n" +
                body;
            out.write(response.getBytes("UTF-8"));
            log("返回错误: " + code + " " + message);
        }
        
        /**
         * 处理JSON-RPC请求
         */
        private String handleJsonRpcRequest(String requestBody) {
            try {
                JsonRpcRequest request = new JsonRpcRequest(requestBody);
                
                // 验证JSON-RPC版本
                if (!"2.0".equals(request.getJsonrpc())) {
                    return JsonRpcResponse.invalidRequest(request.getId()).toString();
                }
                
                String method = request.getMethod();
                JSONObject params = request.getParams();
                
                // 处理MCP标准方法
                switch (method) {
                    case "tools/list":
                        return handleToolsList(request);
                    case "tools/call":
                        return handleToolsCall(request, params);
                    case "initialize":
                        return handleInitialize(request);
                    case "notifications/initialized":
                        // 通知，返回成功响应（兼容HTTP传输）
                        return JsonRpcResponse.success(request.getId(), new JSONObject()).toString();
                    default:
                        return JsonRpcResponse.methodNotFound(request.getId()).toString();
                }
                
            } catch (Exception e) {
                return JsonRpcResponse.parseError().toString();
            }
        }
        
        /**
         * 处理tools/list方法
         */
        private String handleToolsList(JsonRpcRequest request) throws JSONException {
            JSONObject result = new JSONObject();
            result.put("tools", ToolManager.getInstance().getToolsList());
            return JsonRpcResponse.success(request.getId(), result).toString();
        }
        
        /**
         * 处理tools/call方法
         */
        private String handleToolsCall(JsonRpcRequest request, JSONObject params) throws JSONException {
            if (!params.has("name")) {
                return JsonRpcResponse.invalidParams(request.getId(), "缺少必填参数: name").toString();
            }
            
            String toolName = params.getString("name");
            JSONObject arguments = params.optJSONObject("arguments");
            if (arguments == null) {
                arguments = new JSONObject();
            }
            
            JSONObject result = ToolManager.getInstance().callTool(toolName, arguments);
            return JsonRpcResponse.success(request.getId(), result).toString();
        }
        
        /**
         * 处理initialize方法
         */
        private String handleInitialize(JsonRpcRequest request) throws JSONException {
            JSONObject result = new JSONObject();
            JSONObject serverInfo = new JSONObject();
            serverInfo.put("name", "AgentToolbox MCP Server");
            serverInfo.put("version", "1.0.0");
            result.put("serverInfo", serverInfo);
            result.put("protocolVersion", "2024-11-05");
            
            JSONObject capabilities = new JSONObject();
            JSONObject tools = new JSONObject();
            tools.put("listChanges", false);
            capabilities.put("tools", tools);
            result.put("capabilities", capabilities);
            
            return JsonRpcResponse.success(request.getId(), result).toString();
        }
    }
    
}
