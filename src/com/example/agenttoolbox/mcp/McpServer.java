package com.example.agenttoolbox.mcp;

import android.content.Context;
import com.example.agenttoolbox.DeepSeekChatBridge;
import com.example.agenttoolbox.tools.ToolManager;
import org.json.JSONArray;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * MCP 服务端 - 基于HTTP的JSON-RPC 2.0服务 + 静态网页服务
 */
public class McpServer {

    // P0 修复：预编译控制字符过滤正则表达式以提升性能
    private static final Pattern CONTROL_CHARS_PATTERN = 
        Pattern.compile("[\\x00\\x01-\\x08\\x0B-\\x0C\\x0E-\\x1F\\x7F-\\x9F]");

    // 心跳检测超时时间（毫秒），用于长时间运行的工具调用
    // 从 8 秒调整为 30 秒以支持长时间工具执行（如 HTTP 请求、文件操作、命令执行等）
    private static final long HEARTBEAT_TIMEOUT_MS = 30000L;

    private int port;
    private ServerSocket serverSocket;
    private boolean running = false;
    private Thread serverThread;
    private static boolean serverRunning = false;

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

                String requestLine = in.readLine();
                if (requestLine == null) {
                    clientSocket.close();
                    return;
                }

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";

                StringBuilder headersBuilder = new StringBuilder();
                String line;
                int contentLength = 0;

                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    headersBuilder.append(line).append("\n");
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }

                if ("GET".equalsIgnoreCase(method)) {
                    handleGetRequest(path, out);
                } else if ("POST".equalsIgnoreCase(method)) {
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
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
                } catch (IOException ex) {
                    // ignore
                }
            }
        }

        private void handleGetRequest(String path, OutputStream out) throws IOException {
            log("GET请求: " + path);

            String fileName;
            String contentType;

            if ("/".equals(path) || path.isEmpty()) {
                fileName = "test_client.html";
                contentType = "text/html; charset=UTF-8";
            } else {
                fileName = path.startsWith("/") ? path.substring(1) : path;
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
         * 清理请求体中的空字节和不可见字符，防止注入攻击
         */
        private String sanitizeRequestBody(String requestBody) {
            if (requestBody == null) {
                return "";
            }
            // 使用预编译的正则表达式移除所有控制字符
            return CONTROL_CHARS_PATTERN.matcher(requestBody).replaceAll("");
        }

        /**
         * 截断日志以防止过长日志
         */
        private String truncateForLogging(String text, int maxLen) {
            if (text == null) return "";
            if (text.length() > maxLen) {
                return text.substring(0, maxLen) + "... (共 " + text.length() + " 字符)";
            }
            return text;
        }

        private void handlePostRequest(String path, String requestBody, OutputStream out) throws IOException {
            // P0 修复：清理空字节和控制字符
            // 注意：这可能会将某些请求转换为空 JSON 对象（例如 "{\x00}" 变成 "{}"）
            // 这是一种防御性的设计，可以防止含有控制字符的恶意 JSON 被处理
            requestBody = sanitizeRequestBody(requestBody);
            
            // P3 修复：检测空请求
            String trimmedBody = requestBody.trim();
            if (trimmedBody.isEmpty()) {
                // 完全空请求，可能是心跳包，返回 204 No Content
                log("收到空请求（心跳包），已忽略");
                sendNoContentResponse(out);
                return;
            }

            // 优先处理 /api/chat/ 路径（这些端点允许空 JSON 对象 {}）
            if (path.startsWith("/api/chat/")) {
                log("收到聊天 API 请求: " + truncateForLogging(requestBody, 4096));
                handleChatRequest(path, requestBody, out);
                return;
            }
            
            // 对于非 chat 路径，检查是否为空 JSON 对象
            if ("{}".equals(trimmedBody)) {
                // 空 JSON 对象（可能由删除控制字符后产生），无法处理，返回 400 Bad Request
                log("收到空 JSON 对象请求 {}，无法处理");
                sendErrorResponse(out, 400, "Empty request object");
                return;
            }

            // P2 修复：截断日志防止过长
            log("收到请求: " + truncateForLogging(requestBody, 4096));

            String responseBody = handleJsonRpcRequest(requestBody);
            log("返回响应: " + truncateForLogging(responseBody, 4096));

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

        private String extractJsonRpcFromReply(String reply) {
            if (reply == null || reply.length() == 0) return null;

            int idx = reply.indexOf("\"jsonrpc\"");
            if (idx == -1) return null;

            // 从 idx 向前找最近的 { 作为起点
            int start = -1;
            for (int i = idx; i >= 0; i--) {
                if (reply.charAt(i) == '{') {
                    start = i;
                    break;
                }
            }
            if (start == -1) return null;

            // 从 start 用状态机向后扫描，跟踪字符串/转义/嵌套，
            // 找到与最外层 { 匹配的 } 作为结束位置。
            // 这样能正确处理深层嵌套对象和数组，避免截断到内部的 }。
            boolean inString = false;
            char quoteChar = '"';
            boolean escape = false;
            int braceDepth = 1;    // 从 start 的 { 开始
            int bracketDepth = 0;
            int end = -1;
            for (int i = start + 1; i < reply.length(); i++) {
                char c = reply.charAt(i);
                if (inString) {
                    if (escape) { escape = false; continue; }
                    if (c == '\\') { escape = true; continue; }
                    if (c == quoteChar) { inString = false; continue; }
                    continue;
                }
                if (c == '"' || c == '\'') { inString = true; quoteChar = c; continue; }
                if (c == '{') braceDepth++;
                else if (c == '}') {
                    braceDepth--;
                    if (bracketDepth == 0 && braceDepth == 0) {
                        end = i;
                        break;
                    }
                } else if (c == '[') bracketDepth++;
                else if (c == ']') { if (bracketDepth > 0) bracketDepth--; }
            }
            if (end == -1) {
                // 扫描到末尾仍未闭合，说明 reply 本身不完整，
                // 把从 start 到末尾返回给调用方，由后续的 robustCompleteJson 尝试补全。
                return reply.substring(start);
            }
            return reply.substring(start, end + 1);
        }

        /**
         * 当 reply 中 JSON 不完整时，用状态机扫描统计未闭合的 { 与 [ 的数量，
         * 按数量补齐 } 与 ] 后再 JSON.parse 验证，避免 "Unterminated object" 类错误。
         * 返回 合法 JSON 字符串 或 null（无法补全时）。
         */
        private String robustCompleteJson(String partial) {
            if (partial == null || partial.length() == 0) return null;

            // 先尝试直接解析，大多数情况下本身就是完整的
            try {
                new JSONObject(partial);
                return partial;
            } catch (Exception ignore) {}

            // 状态机扫描，跳过字符串字面量与转义
            boolean inString = false;
            char quoteChar = '"';
            boolean escape = false;
            int braceDepth = 0;
            int bracketDepth = 0;
            int firstBrace = -1;
            for (int i = 0; i < partial.length(); i++) {
                char c = partial.charAt(i);
                if (inString) {
                    if (escape) { escape = false; continue; }
                    if (c == '\\') { escape = true; continue; }
                    if (c == quoteChar) { inString = false; continue; }
                    continue;
                }
                if (c == '"' || c == '\'') { inString = true; quoteChar = c; continue; }
                if (c == '{') {
                    if (firstBrace == -1) firstBrace = i;
                    braceDepth++;
                } else if (c == '}') {
                    if (braceDepth > 0) braceDepth--;
                } else if (c == '[') {
                    bracketDepth++;
                } else if (c == ']') {
                    if (bracketDepth > 0) bracketDepth--;
                }
            }
            if (firstBrace == -1) return null;

            String body = partial.substring(firstBrace);
            if (bracketDepth == 0 && braceDepth == 0) {
                // 没有需要补的
                try { new JSONObject(body); return body; } catch (Exception ignore) {}
                return null;
            }
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < bracketDepth; i++) suffix.append(']');
            for (int i = 0; i < braceDepth; i++) suffix.append('}');
            String candidate = body + suffix.toString();
            try {
                new JSONObject(candidate);
                return candidate;
            } catch (Exception e) {
                return null;
            }
        }

        private String executeToolCall(String jsonRpcStr) {
            // 主路径：直接解析 JSON-RPC 调用
            Exception firstFail = null;
            String tryStr = jsonRpcStr;
            try {
                JSONObject req = new JSONObject(tryStr);
                String method = req.optString("method", "");
                if (!"tools/call".equals(method)) {
                    return null;
                }

                JSONObject params = req.optJSONObject("params");
                if (params == null) {
                    return "错误: 缺少 params";
                }

                String toolName = params.optString("name", "");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) {
                    args = new JSONObject();
                }

                log("执行工具: " + toolName);
                JSONObject result = ToolManager.getInstance().callTool(toolName, args);

                JSONArray contentArr = result.optJSONArray("content");
                if (contentArr != null && contentArr.length() > 0) {
                    JSONObject first = contentArr.optJSONObject(0);
                    if (first != null) {
                        return first.optString("text", "");
                    }
                }
                return result.toString();
            } catch (Exception e) {
                firstFail = e;
                log("初次解析失败 (" + e.getMessage() + ")，尝试状态机自动补全");
            }

            // 降级：JSON 不完整时再试 robustCompleteJson 补全
            String completed = robustCompleteJson(tryStr);
            if (completed != null && !completed.equals(tryStr)) {
                try {
                    JSONObject req = new JSONObject(completed);
                    String method = req.optString("method", "");
                    if (!"tools/call".equals(method)) return null;
                    JSONObject params = req.optJSONObject("params");
                    if (params == null) return "错误: 缺少 params";
                    String toolName = params.optString("name", "");
                    JSONObject args = params.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    log("（状态机补全后执行工具: " + toolName + "，补齐了 " + (completed.length() - tryStr.length()) + " 个字符");
                    JSONObject result = ToolManager.getInstance().callTool(toolName, args);
                    JSONArray contentArr = result.optJSONArray("content");
                    if (contentArr != null && contentArr.length() > 0) {
                        JSONObject first = contentArr.optJSONObject(0);
                        if (first != null) return first.optString("text", "");
                    }
                    return result.toString();
                } catch (Exception e) {
                    log("补全后仍失败: " + e.getMessage());
                }
            }
            return "工具执行失败: " + (firstFail != null ? firstFail.getMessage() : "JSON不完整且无法自动补全");
        }

        private void handleChatRequest(String path, String requestBody, final OutputStream out)
                throws IOException {
            String responseBody = "";

            try {
                JSONObject body = requestBody != null && requestBody.length() > 2
                    ? new JSONObject(requestBody) : new JSONObject();
                String action = path;
                if (action.endsWith("/")) {
                    action = action.substring(0, action.length() - 1);
                }

                DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();

                if ("/api/chat/sessions".equals(action)) {
                    if (!bridge.isRegistered()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接，请先打开 DeepSeek 页面")
                            .toString();
                    } else {
                        log("DeepSeek 会话列表查询");
                        String sessionsJson = bridge.getSessions();
                        if (sessionsJson == null) {
                            responseBody = new JSONObject()
                                .put("success", false)
                                .put("error", "提取会话列表失败")
                                .toString();
                        } else {
                            responseBody = new JSONObject()
                                .put("success", true)
                                .put("data", new JSONObject(sessionsJson))
                                .toString();
                        }
                    }
                } else if ("/api/chat/select".equals(action)) {
                    String sessionId = body.optString("session_id", "").trim();
                    if (sessionId.isEmpty()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "session_id 参数不能为空")
                            .toString();
                    } else if (!bridge.isRegistered()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接")
                            .toString();
                    } else {
                        log("DeepSeek 切换会话: " + sessionId);
                        boolean ok = bridge.selectSession(sessionId);
                        responseBody = new JSONObject()
                            .put("success", ok)
                            .put("message", ok ? "已切换会话 " + sessionId : "未找到会话 " + sessionId)
                            .put("session_id", sessionId)
                            .toString();
                    }
                } else if ("/api/chat/new".equals(action)) {
                    if (!bridge.isRegistered()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接")
                            .toString();
                    } else {
                        log("DeepSeek 新建会话");
                        boolean ok = bridge.newSession();
                        responseBody = new JSONObject()
                            .put("success", ok)
                            .put("message", ok ? "已创建新会话" : "无法创建新会话")
                            .toString();
                    }
                } else if ("/api/chat/send".equals(action)) {
                    final String message = body.optString("message", "").trim();
                    if (message.isEmpty()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "message 参数不能为空")
                            .toString();
                    } else if (!bridge.isRegistered()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接，请先打开 DeepSeek 页面并确保已登录")
                            .toString();
                    } else {
                        log("DeepSeek 流式聊天请求: " + message.substring(0, Math.min(50, message.length())));

                        // SSE 头部
                        String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=UTF-8\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Cache-Control: no-cache, no-transform\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Content-Type\r\n" +
                            "Connection: keep-alive\r\n" +
                            "\r\n";
                        out.write(header.getBytes("UTF-8"));
                        out.flush();

                        writeEventChunk(out, "started", new JSONObject().put("ok", true).toString());

                        // 心跳
                        final AtomicReference<Long> lastActivityAt = new AtomicReference<>(System.currentTimeMillis());
                        final AtomicReference<Boolean> stopHeartbeat = new AtomicReference<>(false);
                        // 用于标记是否正在接收工具调用 JSON 流：当检测到工具调用时设为 true，接收完成后设为 false
                        final AtomicReference<Boolean> inToolCallStream = new AtomicReference<>(false);

                        Thread heartbeat = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    int seq = 0;
                                    while (!stopHeartbeat.get()) {
                                        Thread.sleep(HEARTBEAT_TIMEOUT_MS);
                                        if (stopHeartbeat.get()) return;
                                        
                                        // 如果正在接收工具调用 JSON 流，不发送心跳（避免中断 JSON）
                                        if (inToolCallStream.get()) {
                                            continue;
                                        }
                                        
                                        long now = System.currentTimeMillis();
                                        long last = lastActivityAt.get();
                                        if (now - last >= HEARTBEAT_TIMEOUT_MS) {
                                            seq++;
                                            JSONObject j = new JSONObject();
                                            j.put("message", "模型处理中...");
                                            j.put("seq", seq);
                                            j.put("elapsedMs", now - last);
                                            writeEventChunk(out, "status", j.toString());
                                        }
                                    }
                                } catch (InterruptedException ignored) {
                                } catch (Exception ignored) {
                                }
                            }
                        }, "DeepSeekHeartbeat");
                        heartbeat.setDaemon(true);
                        heartbeat.start();

                        // 对话循环
                        String currentMessage = message;
                        int maxRounds = 10;
                        int round = 0;
                        boolean finalDone = false;

                        while (round < maxRounds && !finalDone) {
                            round++;
                            final int currentRound = round;
                            log("对话轮次 " + currentRound);

                            final CountDownLatch roundLatch = new CountDownLatch(1);
                            final AtomicReference<String> roundReplyRef = new AtomicReference<>();
                            final AtomicReference<String> roundErrorRef = new AtomicReference<>();

                            bridge.sendMessageStream(currentMessage,
                                new DeepSeekChatBridge.StreamCallback() {
                                    @Override
                                    public void onChunk(String chunk) {
                                        try {
                                            lastActivityAt.set(System.currentTimeMillis());
                                            if (chunk != null && chunk.startsWith("[STATUS]")) {
                                                JSONObject j = new JSONObject();
                                                j.put("message", chunk);
                                                writeEventChunk(out, "status", j.toString());
                                                return;
                                            }
                                            if (chunk != null && chunk.startsWith("[DEBUG]")) {
                                                log(chunk);
                                                return;
                                            }
                                            // 检测是否为工具调用 JSON（避免把 JSON 当作普通文本塞给用户）
                                            boolean isToolCall = chunk != null
                                                && chunk.indexOf("\"jsonrpc\"") != -1
                                                && chunk.indexOf("\"tools/call\"") != -1;
                                            
                                            // 防止心跳中断工具调用 JSON 流：当检测到工具调用 JSON 时，禁用心跳
                                            if (isToolCall) {
                                                inToolCallStream.set(true);
                                            }
                                            
                                            JSONObject j = new JSONObject();
                                            j.put("content", chunk == null ? "" : chunk);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            writeEventChunk(out, "chunk", j.toString());
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                    }

                                    @Override
                                    public void onDone(String reply) {
                                        try {
                                            roundReplyRef.set(reply);
                                            // 工具调用 JSON 流结束，恢复心跳
                                            inToolCallStream.set(false);
                                            
                                            boolean isToolCall = reply != null
                                                && reply.indexOf("\"jsonrpc\"") != -1
                                                && reply.indexOf("\"tools/call\"") != -1;
                                            // P2 修复：记录 LLM 完整回复（非工具调用时），使用截断防止过长日志
                                            if (!isToolCall && reply != null && reply.length() > 0) {
                                                String logReply = truncateForLogging(reply, 4096);
                                                log("LLM最终回复[轮次" + currentRound + "]: " + logReply);
                                            }
                                            JSONObject j = new JSONObject();
                                            j.put("content", reply == null ? "" : reply);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            writeEventChunk(out, "done", j.toString());
                                            log("轮次 " + currentRound + " 完成，长度=" + (reply == null ? 0 : reply.length())
                                                + (isToolCall ? "（工具调用）" : "（文本回复）"));
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                        roundLatch.countDown();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        try {
                                            roundErrorRef.set(error);
                                            JSONObject j = new JSONObject();
                                            j.put("error", error == null ? "未知错误" : error);
                                            j.put("round", currentRound);
                                            writeEventChunk(out, "error", j.toString());
                                            log("轮次 " + currentRound + " 错误: " + error);
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                        roundLatch.countDown();
                                    }
                                });

                            boolean completed = false;
                            try {
                                completed = roundLatch.await(90, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            if (!completed) {
                                JSONObject j = new JSONObject();
                                j.put("error", "本轮回复超时");
                                writeEventChunk(out, "error", j.toString());
                                break;
                            }

                            if (roundErrorRef.get() != null) {
                                break;
                            }

                            String reply = roundReplyRef.get();
                            if (reply == null || reply.isEmpty()) {
                                log("轮次 " + round + " 回复为空，结束对话");
                                break;
                            }

                            String toolJson = extractJsonRpcFromReply(reply);
                            if (toolJson == null) {
                                finalDone = true;
                                log("对话完成，无更多工具调用");
                                break;
                            }

                            log("检测到工具调用: " + truncateForLogging(toolJson, 4096));
                            log("执行工具中...");
                            String toolResult = executeToolCall(toolJson);
                            log("工具执行结果: " + truncateForLogging(toolResult, 4096));
                            if (toolResult == null || toolResult.isEmpty()) {
                                toolResult = "工具执行返回空结果";
                            }

                            currentMessage = toolResult;

                            JSONObject status = new JSONObject();
                            status.put("message", "工具执行完成，继续对话");
                            writeEventChunk(out, "status", status.toString());
                        }

                        endChunked(out);
                        stopHeartbeat.set(true);
                        log("对话结束，共 " + round + " 轮");
                        return; // 流式路径结束，直接返回
                    } // end of send success block
                } else if ("/api/chat/status".equals(action)) {
                    boolean registered = DeepSeekChatBridge.getInstance().isRegistered();
                    responseBody = new JSONObject()
                        .put("success", true)
                        .put("connected", registered)
                        .put("message", registered ? "DeepSeek 已连接" : "DeepSeek 未连接")
                        .toString();
                } else {
                    responseBody = new JSONObject()
                        .put("success", false)
                        .put("error", "未知聊天接口: " + action)
                        .toString();
                }
            } catch (Exception e) {
                log("聊天请求处理异常: " + e.getMessage());
                try {
                    responseBody = new JSONObject()
                        .put("success", false)
                        .put("error", e.getMessage() != null ? e.getMessage() : "未知错误")
                        .toString();
                } catch (JSONException je) {
                    responseBody = "{\"success\":false,\"error\":\"内部错误\"}";
                }
            }

            // 非流式路径统一发送 JSON 响应
            log("聊天响应:\n" + responseBody);
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

        private void sendNoContentResponse(OutputStream out) throws IOException {
            String response = "HTTP/1.1 204 No Content\r\n" +
                "Content-Length: 0\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n";
            out.write(response.getBytes("UTF-8"));
            log("返回心跳确认: 204 No Content");
        }

        private void writeChunked(OutputStream out, byte[] data) throws IOException {
            if (data == null || data.length == 0) return;
            out.write(Integer.toHexString(data.length).getBytes("UTF-8"));
            out.write("\r\n".getBytes("UTF-8"));
            out.write(data);
            out.write("\r\n".getBytes("UTF-8"));
            out.flush();
        }

        private void writeEventChunk(OutputStream out, String type, String jsonData) throws IOException {
            String event = "event: " + type + "\n" + "data: " + jsonData + "\n\n";
            writeChunked(out, event.getBytes("UTF-8"));
        }

        private void endChunked(OutputStream out) throws IOException {
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }

        private String handleJsonRpcRequest(String requestBody) {
            try {
                JsonRpcRequest request = new JsonRpcRequest(requestBody);

                if (!"2.0".equals(request.getJsonrpc())) {
                    return JsonRpcResponse.invalidRequest(request.getId()).toString();
                }

                String method = request.getMethod();
                JSONObject params = request.getParams();

                switch (method) {
                    case "tools/list":
                        return handleToolsList(request);
                    case "tools/call":
                        return handleToolsCall(request, params);
                    case "initialize":
                        return handleInitialize(request);
                    case "notifications/initialized":
                        return JsonRpcResponse.success(request.getId(), new JSONObject()).toString();
                    default:
                        return JsonRpcResponse.methodNotFound(request.getId()).toString();
                }
            } catch (Exception e) {
                return JsonRpcResponse.parseError().toString();
            }
        }

        private String handleToolsList(JsonRpcRequest request) throws JSONException {
            JSONObject result = new JSONObject();
            result.put("tools", ToolManager.getInstance().getToolsList());
            return JsonRpcResponse.success(request.getId(), result).toString();
        }

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
    } // ClientHandler 结束
} // McpServer 结束
