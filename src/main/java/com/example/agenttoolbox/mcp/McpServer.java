package com.example.agenttoolbox.mcp;

import android.content.Context;
import android.util.Log;
import com.example.agenttoolbox.AppLogger;
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
import java.util.HashMap;
import java.util.Map;
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
        // 也输出到 logcat，便于 adb 调试
        Log.d("McpServer", message);
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
            if (text == null) return "(null)";
            return text;  // 不截断，记录完整内容
        }
        private String truncateForLogging_DISABLED(String text, int maxLen) {
            if (text == null) return "";
            if (text.length() > maxLen) {
                return text;
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
                
                log("[REQ] " + path);
                log("[MCP] 请求体长度: " + (requestBody == null ? 0 : requestBody.length()) + " 字符");
                log("[MCP] 请求:\n" + requestBody);
                
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
            log("[REQ] 请求:\n" + requestBody);

            String responseBody = handleJsonRpcRequest(requestBody);
            log("[RES] 响应:\n" + responseBody);

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
                if (c == '"') { inString = true; quoteChar = c; continue; }
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
            // Fix invalid JSON escapes (e.g. \\x27 -> single quote)
            jsonStr = jsonStr.replace("\\'", "'");
                return new JSONObject(jsonStr);
            } catch (Exception e) {
                // 解析失败：可能是 DeepSeek 网页渲染破坏了字符串值内部的转义双引号
                // 尝试修复未转义的双引号后重试
                String fixed = fixUnescapedQuotes(jsonStr);
                if (!fixed.equals(jsonStr)) {
                    try {
                        JSONObject fixedJson = new JSONObject(fixed);
                        android.util.Log.d("McpServer", "extractJsonObject: 修复未转义双引号后解析成功");
                        return fixedJson;
                    } catch (Exception e2) {
                        android.util.Log.w("McpServer", "extractJsonObject: 修复后仍解析失败: " + e2.getMessage());
                    }
                }
                return null;
            }
        }

        /**
         * 修复 JSON 字符串值内部未转义的双引号。
         * DeepSeek 网页渲染时可能把 JSON 字符串值中的 \" 显示为未转义的 "，
         * 导致 JSONObject 解析失败。
         *
         * 算法：找到 content 字段的值边界，将其内部所有的 " 转义为 \"，
         * 然后重新组装 JSON 字符串。这种方法正确处理嵌套 JSON 作为字符串值的情况。
         */
        private String fixUnescapedQuotes(String jsonStr) {
            if (jsonStr == null || jsonStr.isEmpty()) return jsonStr;
            
            // 找 "content" 字段的值边界
            int ctIdx = jsonStr.indexOf("\"content\"");
            if (ctIdx < 0) ctIdx = jsonStr.indexOf("\"text\"");
            if (ctIdx < 0) return jsonStr;  // 没有需要修复的字段
            
            int colonIdx = jsonStr.indexOf(':', ctIdx + 8);
            if (colonIdx < 0) return jsonStr;
            
            // 找到值的起始引号（跳过空白）
            int valStart = colonIdx + 1;
            while (valStart < jsonStr.length() && Character.isWhitespace(jsonStr.charAt(valStart))) {
                valStart++;
            }
            if (valStart >= jsonStr.length() || jsonStr.charAt(valStart) != '"') return jsonStr;
            valStart++; // 跳过起始引号
            
            // 从末尾往前找值的结束引号（后跟 , 或 } 或 ]）
            int valEnd = -1;
            for (int i = jsonStr.length() - 1; i > valStart; i--) {
                if (jsonStr.charAt(i) == '"') {
                    int j = i + 1;
                    while (j < jsonStr.length() && Character.isWhitespace(jsonStr.charAt(j))) {
                        j++;
                    }
                    if (j < jsonStr.length()) {
                        char next = jsonStr.charAt(j);
                        if (next == ',' || next == '}' || next == ']') {
                            valEnd = i;
                            break;
                        }
                    }
                }
            }
            if (valEnd <= valStart) return jsonStr;
            
            // 提取值内容，转义内部的 "，然后重新组装
            String content = jsonStr.substring(valStart, valEnd);
            String escaped = content.replace("\"", "\\\"");
            
            return jsonStr.substring(0, valStart) + escaped + jsonStr.substring(valEnd);
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
                        log("[REQ] 会话列表");
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
                        log("[REQ] 切换会话: " + sessionId);
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
                        log("[SEND] 消息=" + message);
                        log("[MCP] 消息长度: " + message.length() + " 字符");
                        log("[MCP] 用户消息 (" + message.length() + " 字符): " + message);
                        log("[INIT] 桥接: " + (bridge.isRegistered() ? "已注册" : "未注册"));

                        // 会话缓存：支持客户端传入 conversationId 复用已有会话
                        final long conversationId;
                        SessionCache.SessionData cachedSession = null;
                        boolean isNewSession = false;
                        if (body.has("conversationId")) {
                            long reqId = body.optLong("conversationId", 0);
                            if (reqId > 0 && SessionCache.getInstance().hasSession(reqId)) {
                                conversationId = reqId;
                                cachedSession = SessionCache.getInstance().get(conversationId);
                                log("[SESSION] 复用会话缓存: id=" + conversationId
                                    + " systemPrompt=" + cachedSession.systemPrompt.length() + "字符"
                                    + " isFirstMessage=" + cachedSession.isFirstMessage);
                            } else {
                                log("[SESSION] conversationId=" + reqId + " 缓存不存在，创建新会话");
                                conversationId = conversationIdSeq.incrementAndGet();
                                isNewSession = true;
                            }
                        } else {
                            conversationId = conversationIdSeq.incrementAndGet();
                            isNewSession = true;
                        }
                        log("[INIT] 会话ID: " + conversationId);

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
                        int maxRounds = 100;
                        int round = 0;
                        boolean finalDone = false;
                        int toolCallCount = 0; // 防止工具调用循环
                        // 新会话：创建缓存
                        if (isNewSession) {
                            String systemPrompt = ToolManager.getInstance().getSystemPrompt();
                            JSONArray toolsList = ToolManager.getInstance().getToolsList();
                            JSONObject systemObj = new JSONObject(systemPrompt);
                            cachedSession = new SessionCache.SessionData(systemPrompt, toolsList, systemObj);
                            cachedSession.isFirstMessage = true;
                            SessionCache.getInstance().put(conversationId, cachedSession);
                            log("[SESSION] 新会话缓存已创建: id=" + conversationId
                                + " systemPrompt=" + systemPrompt.length() + "字符");
                        }

                        // 待办计划：检测是否需要生成任务计划
                        if (cachedSession != null) {
                            TaskManager tm = cachedSession.taskManager;
                            if (tm.shouldGeneratePlan(message, cachedSession.planState.tasks.isEmpty() ? 0 : 3)) {
                                log("[PLAN] 触发计划生成，向LLM注入规划提示词");
                                String planPrompt = tm.generatePlanPrompt(message);
                                // 将规划提示词拼接到用户消息中
                                currentMessage = planPrompt;
                            }
                        }

                        while (round < maxRounds && !finalDone) {
                            round++;
                            final int currentRound = round;
                            
                            String messageToSend = currentMessage;
                            if (round == 1 && cachedSession != null && cachedSession.isFirstMessage && !bridge.isDeepseekInitialized()) {
                                // 首次消息：发送完整 system + tools + user
                                try {
                                    JSONObject rpc = new JSONObject();
                                    rpc.put("jsonrpc", "2.0");
                                    rpc.put("method", "initialize");
                                    JSONObject params = new JSONObject();
                                    params.put("system", cachedSession.systemObj);
                                    params.put("user", currentMessage);
                                    rpc.put("params", params);
                                    rpc.put("id", conversationId);
                                    messageToSend = rpc.toString();
                                    // 标记已发送过 system+tools，后续消息不再携带
                                    cachedSession.isFirstMessage = false;
                                    log("[SESSION] 首次消息: 发送完整 system+tools (" + messageToSend.length() + "字符)");
                                    bridge.setDeepseekInitialized(true);
                                } catch (JSONException e) {
                                    messageToSend = cachedSession.systemPrompt;
                                }
                                log("[INIT] 系统提示词: " + messageToSend.length() + " 字符");
                            } else if (round == 1 && cachedSession == null && !bridge.isDeepseekInitialized()) {
                                // 兜底：无缓存时生成 system prompt
                                String systemPrompt = ToolManager.getInstance().getSystemPrompt();
                                try {
                                    JSONObject rpc = new JSONObject();
                                    rpc.put("jsonrpc", "2.0");
                                    rpc.put("method", "initialize");
                                    JSONObject params = new JSONObject();
                                    JSONObject sysObj = new JSONObject(systemPrompt);
                                    params.put("system", sysObj);
                                    params.put("user", currentMessage);
                                    rpc.put("params", params);
                                    rpc.put("id", conversationId);
                                    messageToSend = rpc.toString();
                                } catch (JSONException e) {
                                    messageToSend = systemPrompt;
                                }
                                bridge.setDeepseekInitialized(true);
                                log("[INIT] 系统提示词: " + messageToSend.length() + " 字符");
                            }
                            
                            // 非首轮消息或首轮但未发 initialize 时：如果不是 JSON-RPC 格式，包装为 JSON-RPC system 指令
                            if (round >= 1 && !messageToSend.trim().startsWith("{")) {
                                try {
                                    JSONObject rpc = new JSONObject();
                                    rpc.put("jsonrpc", "2.0");
                                    JSONObject result = new JSONObject();
                                    result.put("type", "user");
                                    result.put("content", messageToSend);
                                    rpc.put("result", result);
                                    rpc.put("id", conversationId);
                                    messageToSend = rpc.toString();
                                    log("[ROUND] 非JSON消息已包装为 JSON-RPC system 指令");
                                } catch (JSONException e) {
                                    log("[ROUND] JSON-RPC 包装失败，使用原始消息");
                                }
                            }
                            
                            log("[ROUND] " + currentRound + "/" + maxRounds + " 开始");
                            
                            log("[LLM] 发送给 LLM (" + (messageToSend == null ? 0 : messageToSend.length()) + " 字符)");
                            log("[ROUND] 进度: " + (currentRound - 1) + "/" + maxRounds);

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
                                                log("[STATUS] " + chunk);
                                                JSONObject j = new JSONObject();
                                                j.put("message", chunk);
                                                writeEventChunk(out, "status", j.toString());
                                                return;
                                            }
                                            if (chunk != null && chunk.startsWith("[DEBUG]")) {
                                                log("[DEBUG] " + chunk);
                                                return;
                                            }
                                            // 检测是否为工具调用 JSON（避免把 JSON 当作普通文本塞给用户）
                                            boolean isToolCall = isToolCallJson(chunk);
                                            log("[LLM] chunk长度=" + (chunk == null ? 0 : chunk.length())
                                                + " isToolCall=" + isToolCall
                                                + " 内容=" + chunk);

                                            // 防止心跳中断工具调用 JSON 流：当检测到工具调用 JSON 时，禁用心跳
                                            if (isToolCall && !inToolCallStream.get()) {
                                                inToolCallStream.set(true);
                                                log("[DEBUG] 工具调用流开始 (长度=" + (chunk == null ? 0 : chunk.length()) + ")");
                                                log("[DEBUG] jsonrpc: " + (chunk.indexOf("\"jsonrpc\":") != -1 ? "Y" : "N"));
                                                log("[DEBUG] method: " + (chunk.indexOf("\"method\"") != -1 ? "Y" : "N"));
                                                log("[DEBUG] tools/call: " + (chunk.indexOf("\"tools/call\"") != -1 ? "Y" : "N"));
                                            }

                                            JSONObject j = new JSONObject();
                                            j.put("content", chunk == null ? "" : chunk);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            writeEventChunk(out, "chunk", j.toString());
                                        } catch (Exception e) {
                                            log("[LLM] chunk异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " chunk=" + chunk);
                                            log("[DEBUG] 堆栈: " + android.util.Log.getStackTraceString(e));
                                        }
                                    }

                                    @Override
                                    public void onDone(String reply) {
                                        try {
                                            log("[LLM] onDone");
                                            log("[LLM] 长度: " + (reply == null ? 0 : reply.length()) + " 字符");
                                            log("[LLM] 空: " + (reply == null || reply.isEmpty() ? "是" : "否"));
                                            if (reply != null && reply.length() > 0) {
                                                log("[LLM] 回复全文:\n" + reply);
                                            }
                                            // 工具调用 JSON 流结束，恢复心跳
                                            if (inToolCallStream.getAndSet(false)) {
                                                log("[DEBUG] 工具调用流完成");
                                            }

                                            boolean isToolCall = isToolCallJson(reply);
                                            log("[LLM] isToolCall=" + isToolCall
                                                + " (jsonrpc=" + (reply != null && reply.indexOf("\"jsonrpc\":") != -1) + ")"
                                                + " (method=" + (reply != null && reply.indexOf("\"method\"") != -1) + ")"
                                                + " (tools/call=" + (reply != null && reply.indexOf("\"tools/call\"") != -1) + ")");

                                            // 解析 canContinue 标记（DeepSeek网页显示"继续生成"按钮）
                                            String cleanReply = reply == null ? "" : reply;
                                            boolean canContinue = false;
                                            if (cleanReply.endsWith("\n__CAN_CONTINUE__") || cleanReply.endsWith("\r\n__CAN_CONTINUE__") || cleanReply.contains("__CAN_CONTINUE__")) {
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
                                                                    log("[LLM] result.content: " + extractedContent.length());
                                                                    // 如果 content 是待办计划 JSON，保留外层 JSON-RPC 包装，
                                                                    // 让主循环的 hasResult 分支正确检测 plan
                                                                    if (extractedContent.trim().startsWith("{\"tasks\"")
                                                                        || resultJson.has("plan_update")) {
                                                                        log("[LLM] result.content 是计划 JSON 或含 plan_update，保留外层包装");
                                                                        // finalReply 不变，保持完整的 JSON-RPC 消息
                                                                    } else {
                                                                        finalReply = extractedContent;
                                                                    }
                                                                }
                                                            }
                                                        } else if (resultObj instanceof String) {
                                                            // result 直接是字符串
                                                            String extractedContent = (String) resultObj;
                                                            if (!extractedContent.isEmpty()) {
                                                                log("[LLM] 提取result(string): " + extractedContent.length());
                                                                finalReply = extractedContent;
                                                            }
                                                        }
                                                    }
                                                } catch (Exception extractEx) {
                                                    log("[LLM] 提取result失败: " + extractEx.getMessage() + "，使用原始回复");
                                                }
                                            }
                                            roundReplyRef.set(finalReply);

                                            // P2 修复：记录 LLM 完整回复（非工具调用时），使用截断防止过长日志
                                            if (!isToolCall && finalReply.length() > 0) {
                                                log("[LLM] 结构: " + analyzeReplyStructure(finalReply));
                                                String logReply = finalReply;
                                                log("[LLM] 内容: " + logReply);
                                            }
                                            JSONObject j = new JSONObject();
                                            j.put("content", finalReply);
                                            j.put("round", currentRound);
                                            j.put("isToolCall", isToolCall);
                                            j.put("canContinue", canContinue);
                                            writeEventChunk(out, "done", j.toString());
                                            log("[ROUND] 完成: 长度=" + finalReply.length()
                                                + " 类型=" + (isToolCall ? "【工具调用】" : "【文本回复】")
                                                + " canContinue=" + canContinue);
                                        } catch (Exception e) {
                                            log("[LLM] onDone异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " reply=" + reply);
                                            log("[DEBUG] 堆栈: " + android.util.Log.getStackTraceString(e));
                                        }
                                        roundLatch.countDown();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        try {
                                            roundErrorRef.set(error);
                                            log("[LLM] onError: " + error);
                                            // 错误时恢复心跳，避免心跳被永久禁用
                                            if (inToolCallStream.getAndSet(false)) {
                                                log("[LLM] 工具调用流错误: " + error);
                                            }

                                            JSONObject j = new JSONObject();
                                            j.put("error", error == null ? "未知错误" : error);
                                            j.put("round", currentRound);
                                            writeEventChunk(out, "error", j.toString());
                                            log("[LLM] 错误: " + error);
                                        } catch (Exception e) {
                                            log("[LLM] onError异常: "
                                                + "类型=" + e.getClass().getName()
                                                + " msg=" + (e.getMessage() == null ? "(null)" : e.getMessage())
                                                + " error=" + error);
                                            log("[DEBUG] 堆栈: " + android.util.Log.getStackTraceString(e));
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
                                log("[ROUND] 等待LLM (最大" + waitSeconds + "秒）");
                                completed = roundLatch.await(waitSeconds, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            if (!completed) {
                                // 超时：尝试从JavaScript端获取任何已有内容
                                log("[ROUND] LLM回复超时");
                                JSONObject j = new JSONObject();
                                j.put("error", "本轮回复超时");
                                writeEventChunk(out, "error", j.toString());
                                break;
                            }

                            if (roundErrorRef.get() != null) {
                                log("[ROUND] 错误: " + roundErrorRef.get() + "，结束对话");
                                break;
                            }

                            String reply = roundReplyRef.get();
                            if (reply == null || reply.isEmpty()) {
                                log("[ROUND] 回复为空，结束对话");
                                break;
                            }
                            // 剥离 __CAN_CONTINUE__ 标记
                            if (reply.contains("__CAN_CONTINUE__")) {
                                reply = reply.replace("\r\n__CAN_CONTINUE__", "").replace("\n__CAN_CONTINUE__", "").replace("__CAN_CONTINUE__", "");
                                log("[LLM] 已剥离 __CAN_CONTINUE__ 标记");
                            }
                            log("[LLM] 回复长度=" + reply.length() + " 字符");

                            // 提取并解析 JSON 回复（JSON-RPC 2.0）
                            JSONObject replyJson = extractJsonObject(reply);
                            if (replyJson == null) {
                                log("[LLM] 无法提取JSON");
                                log("[LLM] 回复全文 (" + reply.length() + " 字符):\n" + reply);
                                finalDone = true;
                                log("[DONE] 对话完成");
                                break;
                            }

                            // JSON-RPC 2.0 分支判断：method=工具调用，result=文本回复，error=错误
                            String method = replyJson.optString("method", "");
                            boolean hasResult = replyJson.has("result");
                            boolean hasError = replyJson.has("error");
                            Object rpcId = replyJson.opt("id");
                            log("[LLM] 解析: method=" + method
                                + " hasResult=" + hasResult + " hasError=" + hasError + " id=" + rpcId);

                            if (hasError) {
                                // LLM 返回错误：当作异常文本回复处理
                                log("[DONE] LLM 返回错误");
                                finalDone = true;
                                break;
                            }

                            if (hasResult && !"tools/call".equals(method)) {
                                // 文本回复：result.type=reply，检查是否包含待办计划
                                JSONObject resultObj = replyJson.optJSONObject("result");
                                String contentType = resultObj != null ? resultObj.optString("type", "") : "";
                                String content = resultObj != null ? resultObj.optString("content", "") : "";
                                
                                // 检测 content 中是否包含待办计划 JSON
                                JSONObject planJson = tryExtractPlan(content);
                                if (planJson != null && cachedSession != null) {
                                    log("[PLAN] 检测到任务计划，自动加载到 PlanState");
                                    cachedSession.taskManager.loadPlan(cachedSession.planState, planJson);
                                    cachedSession.planState.confirmed = true;
                                    log("[PLAN] " + cachedSession.planState.getSummary());
                                    
                                    // 推送 plan 事件到前端
                                    writePlanEvent(out, cachedSession.planState, "created");
                                    
                                    // 选取第一个任务
                                    Task firstTask = cachedSession.taskManager.selectNextTask(cachedSession.planState);
                                    if (firstTask != null) {
                                        log("[PLAN] 开始执行: [" + firstTask.taskId + "] " + firstTask.content);
                                        // 构建结构化计划消息
                                        String planMsg = buildPlanMessage("execute_task", firstTask, cachedSession.planState, conversationId,
                                            "请按计划执行此任务，调用对应工具。完成后在回复中使用 plan_update 推进计划");
                                        if (planMsg != null) {
                                            currentMessage = planMsg;
                                        } else {
                                            currentMessage = cachedSession.planState.toPromptText() + "\n\n[系统指令] 请按计划执行第一个任务。任务: " + firstTask.content;
                                        }
                                        continue; // 继续下一轮，不结束对话
                                    }
                                }
                                
                                // 检测 result 中是否包含 plan_update（LLM 驱动计划更新）
                                if (resultObj != null && resultObj.has("plan_update") && cachedSession != null) {
                                    JSONObject planUpdate = resultObj.optJSONObject("plan_update");
                                    if (planUpdate != null) {
                                        log("[PLAN] 检测到计划更新指令: " + planUpdate.toString());
                                        String planAction = planUpdate.optString("action", "");
                                        TaskManager tm = cachedSession.taskManager;
                                        
                                        switch (planAction) {
                                            case "complete_task":
                                            case "mark_done": {
                                                String taskId = planUpdate.optString("task_id", "");
                                                if (!taskId.isEmpty()) {
                                                    tm.markTaskDone(cachedSession.planState, taskId);
                                                } else if (cachedSession.planState.activeTask != null) {
                                                    tm.markCurrentDone(cachedSession.planState);
                                                }
                                                break;
                                            }
                                            case "mark_failed": {
                                                String taskId = planUpdate.optString("task_id", "");
                                                String reason = planUpdate.optString("reason", "未知错误");
                                                if (!taskId.isEmpty()) {
                                                    tm.markTaskFailed(cachedSession.planState, taskId, reason);
                                                }
                                                break;
                                            }
                                            case "update_plan": {
                                                JSONObject newPlan = planUpdate.optJSONObject("plan");
                                                if (newPlan != null && newPlan.has("tasks")) {
                                                    tm.loadPlan(cachedSession.planState, newPlan);
                                                    cachedSession.planState.confirmed = true;
                                                    writePlanEvent(out, cachedSession.planState, "updated");
                                                }
                                                break;
                                            }
                                        }
                                        
                                        writePlanEvent(out, cachedSession.planState, "progress");
                                        log("[PLAN] " + cachedSession.planState.getSummary());
                                        
                                        // 选择下一个任务
                                        Task nextTask = tm.selectNextTask(cachedSession.planState);
                                        if (nextTask != null) {
                                            String planMsg = buildPlanMessage("execute_task", nextTask, cachedSession.planState, conversationId,
                                                "请调用对应工具执行此任务。完成后在回复中使用 plan_update 推进计划");
                                            if (planMsg != null) {
                                                currentMessage = planMsg;
                                            } else {
                                                currentMessage = cachedSession.planState.toPromptText() + "\n\n[系统指令] 下一步任务: [" + nextTask.taskId + "] " + nextTask.content + "。请调用对应工具执行。";
                                            }
                                            continue;
                                        } else if (cachedSession.planState.allCompleted()) {
                                            String summary = cachedSession.taskManager.generateSummary(cachedSession.planState);
                                            String planMsg = buildPlanMessage("plan_complete", null, cachedSession.planState, conversationId,
                                                "所有任务已完成。请总结执行结果并回复用户。\n" + summary);
                                            if (planMsg != null) {
                                                currentMessage = planMsg;
                                            } else {
                                                currentMessage = "所有任务已完成。\n\n" + summary;
                                            }
                                            finalDone = true;

                                            // 关键修复（与工具分支一致）：主动推送完成总结给前端
                                            try {
                                                writePlanEvent(out, cachedSession.planState, "complete");
                                                JSONObject doneJ = new JSONObject();
                                                doneJ.put("content", content + "\n\n---\n" + summary);
                                                doneJ.put("round", currentRound);
                                                doneJ.put("isToolCall", false);
                                                doneJ.put("planComplete", true);
                                                doneJ.put("canContinue", false);
                                                writeEventChunk(out, "done", doneJ.toString());
                                                flushWriteHandler();
                                                log("[PLAN] 完成总结已推送至前端");
                                            } catch (Exception e) {
                                                log("[PLAN] 推送完成总结失败: " + e.getMessage());
                                            }
                                            break;
                                        }
                                    }
                                }
                                
                                // 普通文本回复，对话结束
                                finalDone = true;
                                log("[DONE] 文本回复");
                                break;
                            }

                            if (!"tools/call".equals(method)) {
                                // 既不是工具调用，也没有 result/error
                                // 先检测是否为纯计划 JSON（LLM 可能直接输出 {"tasks":[...]}）
                                if (replyJson.has("tasks") && replyJson.optJSONArray("tasks") != null && cachedSession != null) {
                                    log("[PLAN] 检测到纯计划 JSON（无 JSON-RPC 包装），自动加载");
                                    cachedSession.taskManager.loadPlan(cachedSession.planState, replyJson);
                                    cachedSession.planState.confirmed = true;
                                    log("[PLAN] " + cachedSession.planState.getSummary());
                                    writePlanEvent(out, cachedSession.planState, "created");
                                    
                                    Task firstTask = cachedSession.taskManager.selectNextTask(cachedSession.planState);
                                    if (firstTask != null) {
                                        log("[PLAN] 开始执行: [" + firstTask.taskId + "] " + firstTask.content);
                                        String planMsg = buildPlanMessage("execute_task", firstTask, cachedSession.planState, conversationId,
                                            "请按计划执行此任务，调用对应工具。完成后在回复中使用 plan_update 推进计划");
                                        if (planMsg != null) {
                                            currentMessage = planMsg;
                                        } else {
                                            currentMessage = cachedSession.planState.toPromptText() + "\n\n[系统指令] 请按计划执行第一个任务。任务: " + firstTask.content;
                                        }
                                        continue;
                                    }
                                }
                                
                                // 格式合规性校验：检测 JSON 结构是否符合 JSON-RPC 2.0 标准
                                String validationError = validateReplyFormat(replyJson);
                                if (validationError != null) {
                                    log("[LLM] 回复格式不合规:\n" + validationError);
                                    // 向 LLM 发送格式化校正提示，让它重新回复
                                    String planMsg = buildPlanMessage("format_error", null, null, conversationId,
                                        validationError + "\n\n请根据以上提示修正你的回复格式，然后重新输出。");
                                    if (planMsg != null) {
                                        currentMessage = planMsg;
                                    } else {
                                        currentMessage = "【系统反馈】\n" + validationError 
                                            + "\n\n请根据以上提示修正你的回复格式，然后重新输出。";
                                    }
                                    continue;
                                }
                                
                                log("[LLM] 未知消息: method=" + method + "，当作文本回复处理");
                                finalDone = true;
                                break;
                            }

                            // 工具调用：method=tools/call
                            JSONObject paramsObj = replyJson.optJSONObject("params");
                            String toolNameForLog = paramsObj != null ? paramsObj.optString("name", "") : "";
                            log("[TOOL] 调用: " + toolNameForLog);
                            log("[TOOL] 调用请求:\n" + replyJson.toString(2));

                            toolCallCount++;
                            // 保护：计划完成后不再强制拦截，由 LLM 自主决定是否继续
                            long toolStartTime = System.currentTimeMillis();
                            String toolResult = null;
                            boolean toolIsError = false;
                            
                            // 路径安全检查：file_read/file_write 路径必须在白名单内
                            if (("file_read".equals(toolNameForLog) || "file_write".equals(toolNameForLog))
                                && paramsObj != null && paramsObj.has("arguments")) {
                                JSONObject args = paramsObj.optJSONObject("arguments");
                                if (args != null) {
                                    String filePath = args.optString("path", "");
                                    if (!filePath.isEmpty() && !filePath.startsWith("/sdcard/") && !filePath.startsWith("/storage/") 
                                        && !filePath.startsWith("/data/local/tmp/")) {
                                        toolResult = "错误: 路径不在安全白名单: " + filePath;
                                        toolIsError = true;
                                        log("[SECURITY] " + toolResult);
                                    }
                                }
                            }
                            if (toolResult == null) {
                                toolResult = executeToolCall(replyJson);
                                if (toolResult == null || toolResult.isEmpty()) {
                                    toolResult = "工具执行返回空结果";
                                }
                                toolIsError = toolResult.startsWith("错误") || toolResult.startsWith("工具执行失败");
                            }

                            // 更新会话中间状态
                            if (cachedSession != null && !toolIsError) {
                                updateSessionState(cachedSession, toolNameForLog, replyJson.optJSONObject("params"), toolResult);
                            }

                            // 待办计划：推送进度到前端（不由系统自动推进，由 LLM 通过 plan_update 控制）
                            if (cachedSession != null) {
                                writePlanEvent(out, cachedSession.planState, "progress");
                            }

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
                            log("[TOOL] 返回给 LLM:\n" + toolResultMsg.toString(2));
                            currentMessage = toolResultMsg.toString();

                            log("[ROUND] 准备下一轮");

                            // 待办计划：每次工具调用后向 LLM 同步最新进度
                            if (cachedSession != null && !cachedSession.planState.tasks.isEmpty()) {
                                log("[PLAN] 当前进度:\n" + cachedSession.planState.toPromptText());
                                // 将计划进度注入到工具结果 JSON 中，保持 JSON-RPC 格式完整
                                try {
                                    JSONObject planInfo = new JSONObject();
                                    planInfo.put("total", cachedSession.planState.totalTasks());
                                    planInfo.put("completed", cachedSession.planState.completedTasks());
                                    planInfo.put("failed", cachedSession.planState.failedTasks());
                                    planInfo.put("summary", cachedSession.planState.getSummary());
                                    if (cachedSession.planState.activeTask != null) {
                                        planInfo.put("active_task", cachedSession.planState.activeTask.taskId);
                                    }
                                    toolResultMsg.put("plan", planInfo);
                                    currentMessage = toolResultMsg.toString();
                                    log("[PLAN] 计划进度已注入工具结果 JSON");
                                } catch (Exception e) {
                                    log("[PLAN] 注入计划进度失败: " + e.getMessage());
                                    currentMessage = cachedSession.planState.toPromptText() + "\n\n[系统指令] 请根据以上任务进度继续执行。\n\n" + currentMessage;
                                }
                                // 检查是否全部完成
                                if (cachedSession.planState.allCompleted()) {
                                    log("[PLAN] 所有任务已完成！");
                                    // 关键修复：计划完成时不再立即结束对话。
                                    // 此前直接 finalDone=true 会让主循环退出，导致最后一轮的工具结果
                                    // (currentMessage=toolResultMsg) 从未发送给 LLM，DeepSeek 网页端
                                    // 看不到工具回复，也无法生成收尾文本。
                                    // 现在改为：保留 currentMessage 作为工具结果让循环继续一轮发给 LLM，
                                    // 同时把完成总结推送给前端测试页
                                    String summary = cachedSession.taskManager.generateSummary(cachedSession.planState);
                                        try {
                                            writePlanEvent(out, cachedSession.planState, "complete");
                                            JSONObject doneJ = new JSONObject();
                                            doneJ.put("content", summary);
                                            doneJ.put("round", currentRound);
                                            doneJ.put("isToolCall", false);
                                            doneJ.put("planComplete", true);
                                            doneJ.put("canContinue", false);
                                            writeEventChunk(out, "done", doneJ.toString());
                                            flushWriteHandler();
                                            log("[PLAN] 完成总结已推送至前端");
                                        } catch (Exception e) {
                                            log("[PLAN] 推送完成总结失败: " + e.getMessage());
                                        }
                                    // 注意：不设置 finalDone，让循环继续一轮把工具结果发回给 LLM
                                    log("[PLAN] 计划已完成，工具结果将回传给 LLM 以生成收尾回复");
                                }
                            }

                            // SSE status 通知（JSON-RPC notification 风格）
                            JSONObject status = new JSONObject();
                            status.put("jsonrpc", "2.0");
                            status.put("method", "status");
                            status.put("id", conversationId);
                            JSONObject statusParams = new JSONObject();
                            statusParams.put("message", "工具执行完成，继续对话");
                            statusParams.put("tool", toolNameForLog);
                            statusParams.put("costMs", System.currentTimeMillis() - toolStartTime);
                            statusParams.put("result", toolResult);
                            statusParams.put("isError", toolIsError);
                            statusParams.put("round", currentRound);
                            // 待办计划进度
                            if (cachedSession != null && !cachedSession.planState.tasks.isEmpty()) {
                                statusParams.put("plan", new JSONObject()
                                    .put("total", cachedSession.planState.totalTasks())
                                    .put("completed", cachedSession.planState.completedTasks())
                                    .put("failed", cachedSession.planState.failedTasks())
                                    .put("summary", cachedSession.planState.getSummary())
                                );
                            }
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

        /**
         * 更新会话中间状态（GM 工具执行后）
         */
        private void updateSessionState(SessionCache.SessionData session, String toolName, JSONObject params, String result) {
            try {
                switch (toolName) {
                    case "gm_root_status":
                        session.intermediateState.put("hasRoot", result.contains("已获取") || result.contains("root"));
                        log("[STATE] hasRoot=" + session.intermediateState.get("hasRoot"));
                        break;
                    case "gm_process_list":
                        session.intermediateState.put("hasProcessList", true);
                        session.intermediateState.put("processListResult", result);
                        log("[STATE] hasProcessList=true");
                        break;
                    case "gm_attach_process":
                        if (params != null && params.has("arguments")) {
                            JSONObject args = params.optJSONObject("arguments");
                            if (args != null && args.has("pid")) {
                                session.intermediateState.put("attachedPid", args.opt("pid"));
                                log("[STATE] attachedPid=" + args.opt("pid"));
                            }
                        }
                        break;
                    case "gm_memory_search":
                        session.intermediateState.put("lastSearchResult", result);
                        log("[STATE] 内存搜索已缓存");
                        break;
                    case "file_read":
                        if (params != null && params.has("arguments")) {
                            JSONObject args = params.optJSONObject("arguments");
                            if (args != null && args.has("path")) {
                                @SuppressWarnings("unchecked")
                                Map<String, String> fileCache = (Map<String, String>) session.intermediateState.get("fileCache");
                                if (fileCache == null) {
                                    fileCache = new HashMap<>();
                                    session.intermediateState.put("fileCache", fileCache);
                                }
                                fileCache.put(args.optString("path"), result);
                                log("[STATE] fileCache: " + args.optString("path"));
                            }
                        }
                        break;
                }
            } catch (Exception e) {
                log("[STATE] 状态更新异常: " + e.getMessage());
            }
        }

        /**
         * 推送 plan 事件到前端（SSE）
         */
        private void writePlanEvent(OutputStream out, PlanState planState, String action) {
            try {
                JSONObject plan = new JSONObject();
                plan.put("action", action);
                plan.put("total", planState.totalTasks());
                plan.put("completed", planState.completedTasks());
                plan.put("failed", planState.failedTasks());
                plan.put("summary", planState.getSummary());

                JSONArray tasks = new JSONArray();
                for (Task t : planState.tasks) {
                    JSONObject tj = new JSONObject();
                    tj.put("task_id", t.taskId);
                    tj.put("content", t.content);
                    tj.put("status", t.status.value());
                    tj.put("priority", t.priority);
                    if (!t.deps.isEmpty()) {
                        JSONArray depsArr = new JSONArray();
                        for (String d : t.deps) depsArr.put(d);
                        tj.put("deps", depsArr);
                    }
                    if (t.failReason != null) tj.put("fail_reason", t.failReason);
                    tasks.put(tj);
                }
                plan.put("tasks", tasks);

                writeEventChunk(out, "plan", plan.toString());
            } catch (Exception e) {
                log("[PLAN] 推送事件失败: " + e.getMessage());
            }
        }

        /**
         * 构建结构化计划消息（JSON-RPC 2.0 system 指令）
         * 替代原来的纯文本 currentMessage，让 LLM 直接解析结构化字段
         */
        private String buildPlanMessage(String action, Task task, PlanState planState, long conversationId, String extraInstruction) {
            try {
                JSONObject rpc = new JSONObject();
                rpc.put("jsonrpc", "2.0");
                JSONObject result = new JSONObject();
                result.put("type", "system");
                result.put("action", action);

                // 任务信息
                if (task != null) {
                    JSONObject taskObj = new JSONObject();
                    taskObj.put("task_id", task.taskId);
                    taskObj.put("content", task.content);
                    taskObj.put("priority", task.priority);
                    if (!task.deps.isEmpty()) {
                        JSONArray depsArr = new JSONArray();
                        for (String d : task.deps) depsArr.put(d);
                        taskObj.put("deps", depsArr);
                    }
                    if (!task.toolNeeds.isEmpty()) {
                        JSONArray tools = new JSONArray();
                        for (String t : task.toolNeeds) tools.put(t);
                        taskObj.put("tool_needs", tools);
                    }
                    if (task.checkpoint != null) taskObj.put("checkpoint", task.checkpoint);
                    result.put("task", taskObj);
                }

                // 计划概览
                if (planState != null) {
                    JSONObject planInfo = new JSONObject();
                    planInfo.put("total", planState.totalTasks());
                    planInfo.put("completed", planState.completedTasks());
                    planInfo.put("failed", planState.failedTasks());
                    planInfo.put("in_progress", planState.activeTask != null ? 1 : 0);
                    result.put("plan", planInfo);
                }

                // 额外指令
                if (extraInstruction != null && !extraInstruction.isEmpty()) {
                    result.put("instruction", extraInstruction);
                }

                rpc.put("result", result);
                rpc.put("id", conversationId);
                return rpc.toString();
            } catch (JSONException e) {
                log("[PLAN] 构建结构化消息失败: " + e.getMessage());
                return null;
            }
        }

        /**
         * 尝试从 LLM 回复文本中提取待办计划 JSON
         * 支持格式：{"tasks":[...]} 或纯文本中嵌入的 JSON
         */
        private JSONObject tryExtractPlan(String content) {
            if (content == null || content.isEmpty()) return null;
            try {
                // 直接解析
                JSONObject json = new JSONObject(content);
                if (json.has("tasks") && json.optJSONArray("tasks") != null) {
                    return json;
                }
            } catch (Exception e) {
                // 不是纯 JSON，尝试从文本中提取
            }
            // 尝试从文本中提取 JSON（LLM 可能在 content 中嵌入 JSON 字符串）
            try {
                int braceIdx = content.indexOf('{');
                if (braceIdx >= 0) {
                    String sub = content.substring(braceIdx);
                    JSONObject json = new JSONObject(sub);
                    if (json.has("tasks") && json.optJSONArray("tasks") != null) {
                        return json;
                    }
                }
            } catch (Exception e) {}
            return null;
        }

        /**
         * 校验 LLM 回复是否符合 JSON-RPC 2.0 格式规范
         * @return 不合规时返回详细的错误描述（含正确格式示例），合规时返回 null
         */
        private String validateReplyFormat(JSONObject replyJson) {
            if (replyJson == null) return null;
            
            boolean hasResult = replyJson.has("result");
            boolean hasMethod = replyJson.has("method");
            boolean hasJsonrpc = "2.0".equals(replyJson.optString("jsonrpc", ""));
            boolean hasType = replyJson.has("type");
            boolean hasContent = replyJson.has("content");
            boolean hasTasks = replyJson.has("tasks");
            
            // 有 result 或 method → 格式基本合规
            if (hasResult || hasMethod) return null;
            
            StringBuilder error = new StringBuilder();
            error.append("【回复格式不合规】\n\n");
            
            if (!hasJsonrpc) {
                error.append("- 缺少 \"jsonrpc\": \"2.0\" 字段\n");
            }
            
            if (hasType && hasContent && !hasResult) {
                error.append("- 使用了 {\"type\":\"reply\",\"content\":...} 结构，但缺少外层的 result 包装\n");
                error.append("- 正确的文本回复格式应为：\n");
                error.append("  {\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"reply\",\"content\":\"...\"},\"id\":1001}\n");
            } else if (!hasResult && !hasMethod) {
                error.append("- 回复中既没有 result 字段（文本回复），也没有 method 字段（工具调用）\n");
                error.append("- 两种允许的回复格式：\n");
                error.append("  1. 文本回复：{\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"reply\",\"content\":\"...\"},\"id\":1001}\n");
                error.append("  2. 工具调用：{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"...\",\"arguments\":{...}},\"id\":1001}\n");
            }
            
            // 如果回复中带有 tasks 但格式错误，额外提示
            if (hasTasks && !hasResult) {
                error.append("- 计划 JSON（{\"tasks\":[...]}）必须放在文本回复的 result.content 字符串内部，不能作为顶层键\n");
            }
            
            error.append("\n请仔细阅读系统提示词中的 reply_formats 定义，按照正确的 JSON-RPC 2.0 格式重新回复。");
            return error.toString();
        }

        private String handleInitialize(JsonRpcRequest request) throws JSONException {
            String strId = request.getId();
            long conversationId;
            try {
                conversationId = Long.parseLong(strId);
            } catch (NumberFormatException e) {
                conversationId = conversationIdSeq.incrementAndGet();
            }
            
            // 生成系统提示词和工具列表，存入会话缓存
            String systemPrompt = ToolManager.getInstance().getSystemPrompt();
            JSONArray toolsList = ToolManager.getInstance().getToolsList();
            JSONObject systemObj = new JSONObject(systemPrompt);
            
            SessionCache.SessionData session = new SessionCache.SessionData(systemPrompt, toolsList, systemObj);
            session.isFirstMessage = true;
            SessionCache.getInstance().put(conversationId, session);
            log("[SESSION] initialize 会话缓存已创建: id=" + conversationId
                + " systemPrompt=" + systemPrompt.length() + "字符"
                + " tools=" + toolsList.length() + "个");

            JSONObject result = new JSONObject();
            JSONObject serverInfo = new JSONObject();
            serverInfo.put("name", "AgentToolbox MCP Server");
            serverInfo.put("version", "1.0.0");
            result.put("serverInfo", serverInfo);
            result.put("protocolVersion", "2024-11-05");
            result.put("conversationId", conversationId);

            JSONObject capabilities = new JSONObject();
            JSONObject tools = new JSONObject();
            tools.put("listChanges", false);
            capabilities.put("tools", tools);
            result.put("capabilities", capabilities);

            return JsonRpcResponse.success(request.getId(), result).toString();
        }
    } // ClientHandler 结束
} // McpServer 结束
