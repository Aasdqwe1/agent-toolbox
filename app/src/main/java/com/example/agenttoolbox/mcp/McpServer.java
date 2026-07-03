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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * MCP 服务端 - 基于HTTP的JSON-RPC 2.0服务 + 静态网页服务
 */
public class McpServer {

    // P0 修复：预编译控制字符过滤正则表达式以提升性能
    private static final Pattern CONTROL_CHARS_PATTERN =
        Pattern.compile("[\\x00\\x01-\\x08\\x0B-\\x0C\\x0E-\\x1F\\x7F-\\x9F]");

    // 会话 ID 生成器：每次对话生成唯一 id，贯穿整条链路（用户→LLM→工具→LLM→用户）
    private static final AtomicLong conversationIdSeq = new AtomicLong(1000);

    // Heartbeat detection timeout (milliseconds) for long-running tool calls
    // Adjusted from 8 seconds to 30 seconds to support long tool execution (HTTP requests, file operations, command execution, etc.)
    private static final long HEARTBEAT_TIMEOUT_MS = 30000L;
    
    // Timeout for waiting on heartbeat thread shutdown during exception recovery
    private static final long HEARTBEAT_THREAD_JOIN_TIMEOUT_MS = 2000L;

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

        ToolManager.getInstance().init(context);

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
        private Handler writeHandler;
        private HandlerThread writeThread;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
                OutputStream out = clientSocket.getOutputStream();

                writeThread = new HandlerThread("SSE-WriteThread");
                writeThread.start();
                writeHandler = new Handler(writeThread.getLooper());

                String requestLine = in.readLine();
                if (requestLine == null) {
                    writeThread.quitSafely();
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
                writeThread.quitSafely();
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
                if (writeThread != null) {
                    writeThread.quitSafely();
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

        /**
         * 分析回复内容结构，返回可读的摘要（用于调试日志）
         */
        private String analyzeReplyStructure(String reply) {
            if (reply == null || reply.isEmpty()) return "[空回复]";
            int len = reply.length();
            int tableCount = 0, codeBlockCount = 0, inlineCodeCount = 0;
            int listCount = 0, headingCount = 0, boldCount = 0, italicCount = 0;
            int linkCount = 0, lineCount = 0, paragraphBreakCount = 0;

            // 统计表
            java.util.regex.Matcher tableMatcher = java.util.regex.Pattern.compile("\\|\\s*[^|\\n]+\\s*\\|\\s*[^|\\n]+\\s*\\|").matcher(reply);
            while (tableMatcher.find()) tableCount++;

            // 统计代码块
            java.util.regex.Matcher codeBlockMatcher = java.util.regex.Pattern.compile("```").matcher(reply);
            while (codeBlockMatcher.find()) codeBlockCount++;

            // 统计行内代码
            java.util.regex.Matcher inlineMatcher = java.util.regex.Pattern.compile("`[^`\\n]+`").matcher(reply);
            while (inlineMatcher.find()) inlineCodeCount++;

            // 列表项
            java.util.regex.Matcher listMatcher = java.util.regex.Pattern.compile("(^|\\n)\\s*[-*+]\\s").matcher(reply);
            while (listMatcher.find()) listCount++;

            // 标题
            java.util.regex.Matcher headingMatcher = java.util.regex.Pattern.compile("(^|\\n)#{1,6}\\s").matcher(reply);
            while (headingMatcher.find()) headingCount++;

            // 粗体
            java.util.regex.Matcher boldMatcher = java.util.regex.Pattern.compile("\\*\\*[^*]+\\*\\*|\\_\\_[^_]+\\_\\_").matcher(reply);
            while (boldMatcher.find()) boldCount++;

            // 斜体
            java.util.regex.Matcher italicMatcher = java.util.regex.Pattern.compile("\\*[^*\n]+\\*|\\_[^_\n]+\\_").matcher(reply);
            while (italicMatcher.find()) italicCount++;

            // 链接
            java.util.regex.Matcher linkMatcher = java.util.regex.Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)").matcher(reply);
            while (linkMatcher.find()) linkCount++;

            // 换行统计
            for (char c : reply.toCharArray()) if (c == '\n') lineCount++;
            java.util.regex.Matcher paraMatcher = java.util.regex.Pattern.compile("\\n\\s*\\n").matcher(reply);
            while (paraMatcher.find()) paragraphBreakCount++;

            StringBuilder sb = new StringBuilder();
            sb.append("[内容摘要 长度=").append(len);
            sb.append(" 行数=").append(lineCount).append(" 段=").append(paragraphBreakCount);
            sb.append(" 标题=").append(headingCount).append(" 列表项=").append(listCount);
            sb.append(" 表格行=").append(tableCount).append(" 代码块=").append(codeBlockCount / 2);
            sb.append(" 行内代码=").append(inlineCodeCount);
            sb.append(" 粗体=").append(boldCount).append(" 斜体=").append(italicCount);
            sb.append(" 链接=").append(linkCount).append("]");
            return sb.toString();
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
                log("════════════════════════════════════════════════════════════");
                log("▶ 收到聊天 API 请求: " + path);
                log("  ├─ 请求体长度: " + (requestBody == null ? 0 : requestBody.length()) + " 字符");
                log("  └─ 请求体内容: " + truncateForLogging(requestBody, 4096));
                log("════════════════════════════════════════════════════════════");
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

        /**
         * 检测文本是否包含工具调用 JSON（JSON-RPC 2.0：method=tools/call）
         */
        private boolean isToolCallJson(String text) {
            if (text == null || text.length() == 0) return false;
            // JSON-RPC 2.0 标准：method="tools/call"
            return text.indexOf("\"method\":\"tools/call\"") != -1
                || text.indexOf("\"method\": \"tools/call\"") != -1
                || text.indexOf("'method':'tools/call'") != -1
                || text.indexOf("'method': 'tools/call'") != -1;
        }

        /**
         * 从回复中提取 JSON 对象（从第一个 { 开始，匹配对应的 }）
         * 返回解析后的 JSONObject，解析失败返回 null
         */
        private JSONObject extractJsonObject(String reply) {
            if (reply == null || reply.length() == 0) return null;

            int idx = reply.indexOf('{');
            if (idx == -1) return null;

            int start = idx;
            boolean inString = false;
            char quoteChar = '"';
            boolean escape = false;
            int braceDepth = 1;
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
            if (end == -1) return null;

            String jsonStr = reply.substring(start, end + 1);
            try {
                return new JSONObject(jsonStr);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 执行工具调用（JSON-RPC 2.0）
         * 输入：{"jsonrpc":"2.0","method":"tools/call","params":{"name":"xxx","arguments":{...}},"id":N}
         * 输出：工具返回的文本结果
         */
        private String executeToolCall(JSONObject replyJson) {
            try {
                JSONObject params = replyJson.optJSONObject("params");
                if (params == null) {
                    return "错误: 缺少 params 字段";
                }
                String toolName = params.optString("name", "");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();

                log("    [executeToolCall] 工具名: " + toolName);
                log("    [executeToolCall] 参数: " + args.toString());

                long start = System.currentTimeMillis();
                JSONObject result = ToolManager.getInstance().callTool(toolName, args);
                long cost = System.currentTimeMillis() - start;
                log("    [executeToolCall] 执行完成，耗时=" + cost + "ms");

                JSONArray contentArr = result.optJSONArray("content");
                if (contentArr != null && contentArr.length() > 0) {
                    JSONObject first = contentArr.optJSONObject(0);
                    if (first != null) {
                        return first.optString("text", "");
                    }
                }
                return result != null ? result.toString() : "(null)";
            } catch (Exception e) {
                log("    [executeToolCall] 执行失败: " + e.getMessage());
                return "工具执行失败: " + e.getMessage();
            }
        }

        private void handleChatRequest(String path, String requestBody, final OutputStream out)
                throws IOException {
            String responseBody = "";
            boolean isStreamingPath = false;
            Thread heartbeatThread = null;
            boolean streamingCompleted = false;

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
                        log("DeepSeek 流式聊天请求: 完整消息=" + message);
                        log("  ├─ 消息长度: " + message.length() + " 字符");
                        log("  ├─ 消息前50字符: " + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
                        log("  ├─ 桥接器状态: " + (bridge.isRegistered() ? "已注册" : "未注册"));
                        log("  └─ 请求ID: stream-" + System.currentTimeMillis());

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
                        
                        // Mark that we have entered the streaming path - for cleanup during exception handling
                        isStreamingPath = true;

                        writeEventChunk(out, "started", new JSONObject().put("ok", true).toString());

                        // 心跳
                        final AtomicReference<Long> lastActivityAt = new AtomicReference<>(System.currentTimeMillis());
                        final AtomicReference<Boolean> stopHeartbeat = new AtomicReference<>(false);
                        // 用于标记是否正在接收工具调用 JSON 流：当检测到工具调用时设为 true，接收完成后设为 false
                        final AtomicBoolean inToolCallStream = new AtomicBoolean(false);

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
                        
                        // Save heartbeat thread reference for cleanup during exception handling
                        heartbeatThread = heartbeat;

                        // Conversation loop
                        String currentMessage = message;
                        int maxRounds = 10;
                        int round = 0;
                        boolean finalDone = false;
                        // 本次对话的唯一会话 ID，贯穿整条链路（initialize / tools/call / 工具结果 / 最终回复）
                        final long conversationId = conversationIdSeq.incrementAndGet();
                        log("  [会话ID] " + conversationId);

                        while (round < maxRounds && !finalDone) {
                            round++;
                            final int currentRound = round;
                            
                            String messageToSend = currentMessage;
                            if (round == 1) {
                                // JSON-RPC 2.0：单条消息封装 system + user
                                // system 提示词已自带 enforce 字段，不再额外拼接 instruction
                                String systemPrompt = ToolManager.getInstance().getSystemPrompt();
                                try {
                                    JSONObject rpc = new JSONObject();
                                    rpc.put("jsonrpc", "2.0");
                                    rpc.put("method", "initialize");
                                    JSONObject params = new JSONObject();
                                    // system 字段直接放提示词 JSON 对象（解析后嵌入，而非字符串拼接）
                                    JSONObject sysObj = new JSONObject(systemPrompt);
                                    params.put("system", sysObj);
                                    params.put("user", currentMessage);
                                    rpc.put("params", params);
                                    // 用本次对话的唯一会话 ID，贯穿整条链路
                                    rpc.put("id", conversationId);
                                    messageToSend = rpc.toString();
                                } catch (JSONException e) {
                                    // 兜底：极少触发
                                    messageToSend = systemPrompt;
                                }
                                log("  [第一轮] 添加系统提示词，消息总长度: " + messageToSend.length() + " 字符");
                            }
                            
                            log("══════════ 对话轮次 " + currentRound + "/" + maxRounds + " 开始 ══════════");
                            log("  ├─ 输入消息长度: " + (messageToSend == null ? 0 : messageToSend.length()) + " 字符");
                            log("  ├─ 输入消息前80字符: " + (messageToSend == null ? "(null)" : (messageToSend.length() > 80 ? messageToSend.substring(0, 80) + "..." : messageToSend)));
                            log("  └─ 已完成轮数: " + (currentRound - 1) + "/" + maxRounds);

                            final CountDownLatch roundLatch = new CountDownLatch(1);
                            final AtomicReference<String> roundReplyRef = new AtomicReference<>();
                            final AtomicReference<String> roundErrorRef = new AtomicReference<>();

                            bridge.sendMessageStream(messageToSend,
                                new DeepSeekChatBridge.StreamCallback() {
                                    @Override
                                    public void onChunk(String chunk) {
                                        try {
                                            lastActivityAt.set(System.currentTimeMillis());
                                            // 清理：去除 NUL 和控制字符（LLM 输出可能混入）
                                            if (chunk != null) {
                                                // 仅保留可打印字符、中文、换行
                                                StringBuilder sb = new StringBuilder();
                                                for (int ci = 0; ci < chunk.length(); ci++) {
                                                    char c = chunk.charAt(ci);
                                                    if (c == 0 || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                                                        continue; // 跳过控制字符
                                                    }
                                                    sb.append(c);
                                                }
                                                chunk = sb.toString();
                                            }
                                            if (chunk != null && chunk.startsWith("[STATUS]")) {
                                                log("  [轮次" + currentRound + "] [STATUS] " + chunk);
                                                JSONObject j = new JSONObject();
                                                j.put("message", chunk);
                                                writeEventChunk(out, "status", j.toString());
                                                return;
                                            }
                                            if (chunk != null && chunk.startsWith("[DEBUG]")) {
                                                log("  [轮次" + currentRound + "] [DEBUG] " + chunk);
                                                return;
                                            }
                                            // 检测是否为工具调用 JSON（避免把 JSON 当作普通文本塞给用户）
                                            boolean isToolCall = isToolCallJson(chunk);
                                            log("  [轮次" + currentRound + "] 收到chunk: 长度=" + (chunk == null ? 0 : chunk.length())
                                                + " isToolCall=" + isToolCall
                                                + " 内容=" + truncateForLogging(chunk, 200));

                                            // 防止心跳中断工具调用 JSON 流：当检测到工具调用 JSON 时，禁用心跳
                                            if (isToolCall && !inToolCallStream.get()) {
                                                inToolCallStream.set(true);
                                                log("  [轮次" + currentRound + "] 【P3修复】检测到工具调用 JSON 流开始，禁用心跳 (chunk长度=" + (chunk == null ? 0 : chunk.length()) + ")");
                                                log("    ├─ jsonrpc标记: " + (chunk.indexOf("\"jsonrpc\":") != -1 ? "✓" : "✗"));
                                                log("    ├─ method标记: " + (chunk.indexOf("\"method\"") != -1 ? "✓" : "✗"));
                                                log("    └─ tools/call标记: " + (chunk.indexOf("\"tools/call\"") != -1 ? "✓" : "✗"));
                                            }

                                            JSONObject j = new JSONObject();
                                            j.put("content", chunk == null ? "" : chunk);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            writeEventChunk(out, "chunk", j.toString());
                                        } catch (Exception e) {
                                            log("  [轮次" + currentRound + "] chunk处理异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " chunk前200=" + truncateForLogging(chunk, 200));
                                            log("  [轮次" + currentRound + "] 堆栈: " + android.util.Log.getStackTraceString(e));
                                        }
                                    }

                                    @Override
                                    public void onDone(String reply) {
                                        try {
                                            log("  [轮次" + currentRound + "] onDone 触发:");
                                            log("    ├─ 回复长度: " + (reply == null ? 0 : reply.length()) + " 字符");
                                            log("    ├─ 回复是否为空: " + (reply == null || reply.isEmpty() ? "是" : "否"));
                                            if (reply != null && reply.length() > 0) {
                                                log("    ├─ 回复前150字符: " + (reply.length() > 150 ? reply.substring(0, 150) + "..." : reply));
                                                log("    └─ 回复后100字符: " + (reply.length() > 100 ? "..." + reply.substring(reply.length() - 100) : reply));
                                            }
                                            // 工具调用 JSON 流结束，恢复心跳
                                            if (inToolCallStream.getAndSet(false)) {
                                                log("  [轮次" + currentRound + "] 【P3修复】工具调用 JSON 流已完成，恢复心跳");
                                            }

                                            boolean isToolCall = isToolCallJson(reply);
                                            log("  [轮次" + currentRound + "] 回复类型检测: isToolCall=" + isToolCall
                                                + " (jsonrpc=" + (reply != null && reply.indexOf("\"jsonrpc\":") != -1) + ")"
                                                + " (method=" + (reply != null && reply.indexOf("\"method\"") != -1) + ")"
                                                + " (tools/call=" + (reply != null && reply.indexOf("\"tools/call\"") != -1) + ")");

                                            // 解析 canContinue 标记（DeepSeek网页显示"继续生成"按钮）
                                            String cleanReply = reply == null ? "" : reply;
                                            boolean canContinue = false;
                                            if (cleanReply.endsWith("\n__CAN_CONTINUE__")) {
                                                canContinue = true;
                                                cleanReply = cleanReply.substring(0, cleanReply.length() - "__CAN_CONTINUE__".length() - 1);
                                            }
                                            String finalReply = cleanReply;

                                            // 如果回复是 JSON-RPC result 格式（文本回复），提取 result.content
                                            // 避免把整个 JSON-RPC 字符串当作显示内容
                                            if (!isToolCall && finalReply.length() > 0) {
                                                try {
                                                    JSONObject parsed = extractJsonObject(finalReply);
                                                    if (parsed != null && parsed.has("result") && !parsed.has("method")) {
                                                        Object resultObj = parsed.get("result");
                                                        if (resultObj instanceof JSONObject) {
                                                            JSONObject resultJson = (JSONObject) resultObj;
                                                            if (resultJson.has("content")) {
                                                                String extractedContent = resultJson.getString("content");
                                                                if (extractedContent != null && !extractedContent.isEmpty()) {
                                                                    log("  [轮次" + currentRound + "] 提取 JSON-RPC result.content，长度=" + extractedContent.length());
                                                                    finalReply = extractedContent;
                                                                }
                                                            }
                                                        } else if (resultObj instanceof String) {
                                                            // result 直接是字符串
                                                            String extractedContent = (String) resultObj;
                                                            if (!extractedContent.isEmpty()) {
                                                                log("  [轮次" + currentRound + "] 提取 JSON-RPC result (string)，长度=" + extractedContent.length());
                                                                finalReply = extractedContent;
                                                            }
                                                        }
                                                    }
                                                } catch (Exception extractEx) {
                                                    log("  [轮次" + currentRound + "] 提取 result.content 失败: " + extractEx.getMessage() + "，使用原始回复");
                                                }
                                            }
                                            roundReplyRef.set(finalReply);

                                            // P2 修复：记录 LLM 完整回复（非工具调用时），使用截断防止过长日志
                                            if (!isToolCall && finalReply.length() > 0) {
                                                log("  [轮次" + currentRound + "] LLM最终回复结构: " + analyzeReplyStructure(finalReply));
                                                String logReply = truncateForLogging(finalReply, 4096);
                                                log("  [轮次" + currentRound + "] LLM最终回复内容: " + logReply);
                                            }
                                            JSONObject j = new JSONObject();
                                            j.put("content", finalReply);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            j.put("canContinue", canContinue);
                                            writeEventChunk(out, "done", j.toString());
                                            log("  [轮次" + currentRound + "] 轮次完成: 长度=" + finalReply.length()
                                                + " 类型=" + (isToolCall ? "【工具调用】" : "【文本回复】")
                                                + " canContinue=" + canContinue);
                                        } catch (Exception e) {
                                            log("  [轮次" + currentRound + "] onDone处理异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " reply前200=" + truncateForLogging(reply, 200));
                                            log("  [轮次" + currentRound + "] 堆栈: " + android.util.Log.getStackTraceString(e));
                                        }
                                        roundLatch.countDown();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        try {
                                            roundErrorRef.set(error);
                                            log("  [轮次" + currentRound + "] onError 触发: " + error);
                                            // 错误时恢复心跳，避免心跳被永久禁用
                                            if (inToolCallStream.getAndSet(false)) {
                                                log("  [轮次" + currentRound + "] 【P3修复】工具调用 JSON 流发生错误，恢复心跳: " + error);
                                            }

                                            JSONObject j = new JSONObject();
                                            j.put("error", error == null ? "未知错误" : error);
                                            j.put("round", currentRound);
                                            writeEventChunk(out, "error", j.toString());
                                            log("  [轮次" + currentRound + "] 错误: " + error);
                                        } catch (Exception e) {
                                            log("  [轮次" + currentRound + "] onError处理异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " error前200=" + truncateForLogging(error, 200));
                                            log("  [轮次" + currentRound + "] 堆栈: " + android.util.Log.getStackTraceString(e));
                                        }
                                        roundLatch.countDown();
                                    }
                                });

                            boolean completed = false;
                            try {
                                // 动态超时：工具调用场景可能需要更长的LLM生成时间
                                // 普通回复给600秒（10分钟），工具调用给更长时间
                                // 注意：实际轮次结束由JavaScript端的pollOnce触发，这里只是兜底
                                long waitSeconds = 600; // 默认10分钟
                                if (round > 1) waitSeconds = 1800; // 后续轮次可能是工具调用，给30分钟
                                log("轮次 " + currentRound + " 等待LLM回复（最大" + waitSeconds + "秒）");
                                completed = roundLatch.await(waitSeconds, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            if (!completed) {
                                // 超时：尝试从JavaScript端获取任何已有内容
                                log("轮次 " + currentRound + " LLM回复超时（roundLatch.await超时）");
                                JSONObject j = new JSONObject();
                                j.put("error", "本轮回复超时");
                                writeEventChunk(out, "error", j.toString());
                                break;
                            }

                            if (roundErrorRef.get() != null) {
                                log("  [轮次" + currentRound + "] 检测到错误: " + roundErrorRef.get() + "，结束对话");
                                break;
                            }

                            String reply = roundReplyRef.get();
                            if (reply == null || reply.isEmpty()) {
                                log("  [轮次" + currentRound + "] 回复为空，结束对话");
                                // 空回复：不继续轮询，结束对话
                                break;
                            }
                            log("  [轮次" + currentRound + "] 获取回复长度=" + reply.length() + " 字符");

                            // 提取并解析 JSON 回复（JSON-RPC 2.0）
                            JSONObject replyJson = extractJsonObject(reply);
                            if (replyJson == null) {
                                log("  [轮次" + currentRound + "] ⚠ 警告: 无法从回复中提取JSON对象");
                                log("  [轮次" + currentRound + "]   └─ 回复前200字符: " + truncateForLogging(reply, 200));
                                finalDone = true;
                                log("  [轮次" + currentRound + "] ═══ 对话完成 ═══");
                                break;
                            }

                            // JSON-RPC 2.0 分支判断：method=工具调用，result=文本回复，error=错误
                            String method = replyJson.optString("method", "");
                            boolean hasResult = replyJson.has("result");
                            boolean hasError = replyJson.has("error");
                            Object rpcId = replyJson.opt("id");
                            log("  [轮次" + currentRound + "] 解析成功: method=" + method
                                + " hasResult=" + hasResult + " hasError=" + hasError + " id=" + rpcId);

                            if (hasError) {
                                // LLM 返回错误：当作异常文本回复处理
                                log("  [轮次" + currentRound + "] ═══ 对话完成（LLM 返回 error） ═══");
                                finalDone = true;
                                break;
                            }

                            if (hasResult && !"tools/call".equals(method)) {
                                // 文本回复：result.type=reply，对话结束
                                finalDone = true;
                                log("  [轮次" + currentRound + "] ═══ 对话完成（文本回复） ═══");
                                break;
                            }

                            if (!"tools/call".equals(method)) {
                                // 既不是工具调用，也没有 result/error：当作文本回复处理
                                log("  [轮次" + currentRound + "] ⚠ 未知 JSON-RPC 消息: method=" + method + "，当作文本回复处理");
                                finalDone = true;
                                break;
                            }

                            // 工具调用：method=tools/call
                            JSONObject paramsObj = replyJson.optJSONObject("params");
                            String toolNameForLog = paramsObj != null ? paramsObj.optString("name", "") : "";
                            log("  [轮次" + currentRound + "] 执行工具: " + toolNameForLog);
                            log("  [轮次" + currentRound + "] LLM tools/call 请求 JSON: " + replyJson.toString());

                            long toolStartTime = System.currentTimeMillis();
                            String toolResult = executeToolCall(replyJson);
                            long toolCostMs = System.currentTimeMillis() - toolStartTime;

                            log("  [轮次" + currentRound + "] 工具执行完成: 耗时=" + toolCostMs + "ms");
                            log("  [轮次" + currentRound + "] 工具原始返回: " + truncateForLogging(toolResult, 500));
                            if (toolResult == null || toolResult.isEmpty()) {
                                toolResult = "工具执行返回空结果";
                            }
                            boolean toolIsError = toolResult.startsWith("错误") || toolResult.startsWith("工具执行失败");

                            // 构造 JSON-RPC 2.0 工具结果响应，发送给 LLM 继续对话
                            JSONObject toolResultMsg = new JSONObject();
                            try {
                                toolResultMsg.put("jsonrpc", "2.0");
                                if (toolIsError) {
                                    JSONObject errObj = new JSONObject();
                                    errObj.put("code", -32603);
                                    errObj.put("message", toolResult);
                                    toolResultMsg.put("error", errObj);
                                } else {
                                    JSONObject resultObj = new JSONObject();
                                    JSONArray contentArr = new JSONArray();
                                    contentArr.put(new JSONObject().put("type", "text").put("text", toolResult));
                                    resultObj.put("content", contentArr);
                                    toolResultMsg.put("result", resultObj);
                                }
                                // 工具结果响应统一使用会话 ID，保证整条链路 id 一致
                                // LLM 可能自创 id（如 1、10 等），服务端始终回会话 ID 以"纠正"链路
                                toolResultMsg.put("id", conversationId);
                            } catch (Exception ignored) {}
                            log("  [轮次" + currentRound + "] 服务端工具结果 JSON: " + toolResultMsg.toString());
                            currentMessage = toolResultMsg.toString();
                            log("  [轮次" + currentRound + "] 准备进入下一轮对话...");

                            // SSE status 通知（JSON-RPC notification 风格）
                            JSONObject status = new JSONObject();
                            status.put("jsonrpc", "2.0");
                            status.put("method", "status");
                            status.put("id", conversationId);
                            JSONObject statusParams = new JSONObject();
                            statusParams.put("message", "工具执行完成，继续对话");
                            statusParams.put("tool", toolNameForLog);
                            statusParams.put("costMs", toolCostMs);
                            statusParams.put("result", toolResult);
                            statusParams.put("isError", toolIsError);
                            statusParams.put("round", currentRound);
                            status.put("params", statusParams);
                            writeEventChunk(out, "status", status.toString());
                        }

                        // 刷新所有待处理的写入任务，确保所有块都已发送
                        flushWriteHandler();
                        // 然后发送 chunked 编码终止符
                        endChunked(out);
                        stopHeartbeat.set(true);
                        streamingCompleted = true;  // Mark that streaming completed normally
                        log("=========== Conversation ended, total " + round + " rounds ===========");
                        return; // Streaming path ended, return
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
                log("Chat request processing exception: "
                    + "type=" + e.getClass().getName()
                    + " message=" + (e.getMessage() == null ? "(null)" : e.getMessage()));
                log("Chat request processing exception: Stack trace: " + android.util.Log.getStackTraceString(e));
                
                // If exception occurred while in streaming path, need to terminate streaming
                if (isStreamingPath) {
                    log("Chat exception occurred while in streaming path, preparing to clean up resources...");
                } else {
                    try {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", e.getMessage() != null ? e.getMessage() : "Unknown error")
                            .toString();
                    } catch (JSONException je) {
                        responseBody = "{\"success\":false,\"error\":\"Internal error\"}";
                    }
                }
            } finally {
                // If entered streaming path but didn't complete normally, ensure streaming properly terminates
                if (isStreamingPath && !streamingCompleted) {
                    log("Entering exception recovery process (streaming did not complete normally)");
                    try {
                        // Stop heartbeat thread
                        Thread hb = heartbeatThread;
                        if (hb != null) {
                            log("Shutting down heartbeat thread...");
                            // Stop heartbeat thread via interrupt (more reliable method)
                            hb.interrupt();
                            // Wait for thread to end, but set timeout to prevent deadlock
                            try {
                                hb.join(HEARTBEAT_THREAD_JOIN_TIMEOUT_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } catch (Exception ex) {
                        log("Exception shutting down heartbeat thread ("
                            + ex.getClass().getName() + "): " + ex.getMessage());
                    }
                    
                    try {
                        // Flush all pending write tasks in the buffer
                        flushWriteHandler();
                    } catch (Exception ex) {
                        log("Exception flushing write handler ("
                            + ex.getClass().getName() + "): " + ex.getMessage());
                    }
                    
                    try {
                        // Send chunked encoding terminator
                        log("Sending streaming terminator...");
                        endChunked(out);
                    } catch (Exception ex) {
                        log("Exception sending terminator ("
                            + ex.getClass().getName() + "): " + ex.getMessage());
                    }
                    
                    return; // Streaming path ended, return
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

        /**
         * Check if an IOException is due to socket being closed.
         * Socket closure is expected behavior when client disconnects.
         */
        private boolean isSocketClosed(IOException e) {
            String message = e.getMessage();
            if (message != null) {
                return message.contains("Socket closed") || 
                       message.contains("Connection reset") ||
                       message.contains("Broken pipe") ||
                       message.contains("Connection closed") ||
                       message.contains("Connection timed out") ||
                       message.contains("Timed out");
            }
            return e instanceof java.net.SocketException;
        }

        private void writeEventChunk(final OutputStream out, final String type, final String jsonData) {
            writeHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (out) {
                            String event = "event: " + type + "\n" + "data: " + jsonData + "\n\n";
                            byte[] data = event.getBytes("UTF-8");
                            out.write(Integer.toHexString(data.length).getBytes("UTF-8"));
                            out.write("\r\n".getBytes("UTF-8"));
                            out.write(data);
                            out.write("\r\n".getBytes("UTF-8"));
                            out.flush();
                        }
                    } catch (IOException e) {
                        // Socket closed is expected when client disconnects, only log other IOExceptions
                        if (!isSocketClosed(e)) {
                            log("SSE写入异常: " + e.getMessage());
                        }
                    }
                }
            });
        }

        /**
         * 等待所有待处理的写入任务完成
         * 确保在发送 chunked 编码终止符之前，所有的事件块都已经写入
         */
        private void flushWriteHandler() {
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                writeHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        latch.countDown();
                    }
                });
                // 等待同步任务完成，但设置超时以防止死锁（最多等待5秒）
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("flushWriteHandler 被中断: " + e.getMessage());
            }
        }

        private void endChunked(final OutputStream out) {
            try {
                synchronized (out) {
                    // 写入最终的 chunked 编码终止符（0\r\n\r\n）
                    // 必须同步写入，确保在连接关闭前完成写入
                    // 这是 HTTP chunked transfer encoding 的必需部分
                    out.write("0\r\n\r\n".getBytes("UTF-8"));
                    out.flush();
                    log("已发送 chunked 编码终止符");
                }
            } catch (IOException e) {
                // Socket closed is expected when client disconnects, only log other IOExceptions
                if (!isSocketClosed(e)) {
                    log("SSE结束写入异常: " + e.getMessage());
                }
            }
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
