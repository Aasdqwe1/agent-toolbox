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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
				handleChatRequest(path, requestBody, out);  // 直接调用，不需要 try-catch
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
         * 注意: throws JSONException 已添加，内部 try-catch 会捕获所有异常
         */
        private void handleChatRequest(String path, String requestBody, final OutputStream out) 
		throws IOException {
            String responseBody;
            try {
                JSONObject body = requestBody != null && requestBody.length() > 2
                    ? new JSONObject(requestBody) : new JSONObject();
                String action = path;
                // 允许末尾斜杠
                if (action.endsWith("/")) action = action.substring(0, action.length() - 1);

                DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();

                if ("/api/chat/sessions".equals(action)) {
                    // 获取会话列表
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
                            // sessionsJson 本身就是完整 JSON，直接包一层 success
                            responseBody = new JSONObject()
                                .put("success", true)
                                .put("data", new JSONObject(sessionsJson))
                                .toString();
                        }
                    }
                } else if ("/api/chat/select".equals(action)) {
                    // 切换会话
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
                    // 创建新会话
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
                    // 流式发送：SSE / Transfer-Encoding: chunked
                    // 策略：
                    //   1. 启动心跳守护线程，每 15 秒输出一次 status 事件，避免连接被认为超时
                    //   2. 先尝试流式请求（最长 180 秒/含降级逻辑）
                    //   3. 若流式长时间无内容（首次 35 秒内没收到 chunk），自动重试一次
                    //   4. 若重试后仍失败，降级到阻塞式 sendMessage
                    final String message = body != null ? body.optString("message", "").trim() : "";
                    if (message.isEmpty()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "message 参数不能为空")
                            .toString();
                    } else if (!DeepSeekChatBridge.getInstance().isRegistered()) {
                        responseBody = new JSONObject()
                            .put("success", false)
                            .put("error", "DeepSeek 未连接，请先打开 DeepSeek 页面并确保已登录")
                            .toString();
                    } else {
                        log("DeepSeek 流式聊天请求: " + message.substring(0, Math.min(50, message.length())));

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

                        // 每 15 秒一个心跳 status 事件，避免浏览器/中间层认为超时
                        final java.util.concurrent.atomic.AtomicReference<Long> lastActivityAt =
                            new java.util.concurrent.atomic.AtomicReference<Long>(System.currentTimeMillis());
                        final java.util.concurrent.atomic.AtomicReference<Thread> heartbeatThreadRef =
                            new java.util.concurrent.atomic.AtomicReference<Thread>();
                        final java.util.concurrent.atomic.AtomicReference<Boolean> stopHeartbeat =
                            new java.util.concurrent.atomic.AtomicReference<Boolean>(Boolean.FALSE);

                        Thread heartbeat = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    int seq = 0;
                                    while (!stopHeartbeat.get()) {
                                        Thread.sleep(15000);
                                        if (stopHeartbeat.get()) return;
                                        long now = System.currentTimeMillis();
                                        long last = lastActivityAt.get();
                                        // 只有在"流式回调确实一直没 activity"时才补心跳，避免覆盖真实数据
                                        if (now - last >= 14000) {
                                            seq++;
                                            JSONObject j = new JSONObject();
                                            j.put("message", "模型处理中...");
                                            j.put("seq", seq);
                                            j.put("elapsedMs", now - last);
                                            writeEventChunk(out, "status", j.toString());
                                        }
                                    }
                                } catch (InterruptedException ignored) {
                                } catch (Exception ignored) { }
                            }
                        }, "DeepSeekHeartbeat");
                        heartbeat.setDaemon(true);
                        heartbeatThreadRef.set(heartbeat);
                        heartbeat.start();

                        // ====== 执行一次流式请求的辅助方法（闭包形式放在下面的 Runnable 内） ======
                        final java.util.concurrent.atomic.AtomicReference<String> finalReplyRef =
                            new java.util.concurrent.atomic.AtomicReference<String>();
                        final java.util.concurrent.atomic.AtomicReference<String> errRef =
                            new java.util.concurrent.atomic.AtomicReference<String>();

                        boolean finishedNormally = false;
                        int attempt = 0;
                        int maxAttempts = 2;  // 第 1 次 + 1 次自动重试

                        while (attempt < maxAttempts && !finishedNormally) {
                            attempt++;
                            final int curAttempt = attempt;

                            final CountDownLatch attemptLatch = new CountDownLatch(1);
                            final java.util.concurrent.atomic.AtomicReference<String> attemptReply =
                                new java.util.concurrent.atomic.AtomicReference<String>();
                            final java.util.concurrent.atomic.AtomicReference<String> attemptErr =
                                new java.util.concurrent.atomic.AtomicReference<String>();
                            final java.util.concurrent.atomic.AtomicReference<Boolean> gotAnyChunk =
                                new java.util.concurrent.atomic.AtomicReference<Boolean>(Boolean.FALSE);

                            DeepSeekChatBridge.getInstance().sendMessageStream(message,
                                new DeepSeekChatBridge.StreamCallback() {
                                    @Override
                                    public void onChunk(String chunk) {
                                        try {
                                            lastActivityAt.set(System.currentTimeMillis());
                                            // 区分心跳 STATUS 和真实内容
                                            if (chunk != null && chunk.startsWith("[STATUS]")) {
                                                JSONObject j = new JSONObject();
                                                j.put("message", chunk);
                                                writeEventChunk(out, "status", j.toString());
                                                return;
                                            }
                                            // 调试日志：输出到应用日志
                                            if (chunk != null && chunk.startsWith("[DEBUG]")) {
                                                log(chunk);
                                                return;
                                            }
                                            gotAnyChunk.set(true);
                                            JSONObject j = new JSONObject();
                                            j.put("content", chunk == null ? "" : chunk);
                                            j.put("attempt", curAttempt);
                                            writeEventChunk(out, "chunk", j.toString());
                                            if (chunk != null && chunk.length() > 30) {
                                                log("流式回复片段(" + curAttempt + "): " + chunk.substring(0, 30) + "...");
                                            }
                                        } catch (Exception e) { /* ignore */ }
                                    }
                                    @Override
                                    public void onDone(String reply) {
                                        try {
                                            attemptReply.set(reply);
                                            finalReplyRef.set(reply);
                                            JSONObject j = new JSONObject();
                                            j.put("content", reply == null ? "" : reply);
                                            j.put("attempt", curAttempt);
                                            writeEventChunk(out, "done", j.toString());
                                            log("流式回复完成(" + curAttempt + "): " +
                                                (reply == null ? "空" : reply.length() + " 字节, 内容预览: " + reply.substring(0, Math.min(80, reply.length()))));
                                        } catch (Exception e) { /* ignore */ }
                                        attemptLatch.countDown();
                                    }
                                    @Override
                                    public void onError(String error) {
                                        try {
                                            attemptErr.set(error);
                                            errRef.set(error);
                                            JSONObject j = new JSONObject();
                                            j.put("error", error == null ? "未知错误" : error);
                                            j.put("attempt", curAttempt);
                                            writeEventChunk(out, "error", j.toString());
                                            log("流式回复错误(" + curAttempt + "): " + error);
                                        } catch (Exception e) { /* ignore */ }
                                        attemptLatch.countDown();
                                    }
                                });

                            // 超时策略：首次收到内容前给 35 秒；若收到 chunk，延长到 180 秒
                            long waited = 0;
                            long stepMs = 1000;
                            long softLimitMs = 35000;
                            long hardLimitMs = 180000;
                            boolean timedOut = false;
                            while (waited < hardLimitMs) {
                                // 每 1 秒轮询一次 latch，避免长时间阻塞导致无法响应中断
                                boolean ok = false;
                                try {
                                    ok = attemptLatch.await(stepMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                waited += stepMs;
                                if (ok) break;
                                // 若 35 秒内还没收到任何真实 chunk 且没得到 done/error，判定为需要重试
                                if (waited >= softLimitMs && !gotAnyChunk.get() && attemptLatch.getCount() > 0) {
                                    timedOut = true;
                                    break;
                                }
                            }

                            if (timedOut) {
                                // 首次请求长时间没任何 chunk，通常是输入框没定位、按钮点击失败等
                                log("流式请求(" + attempt + ") 长时间无内容，判定超时 " + (waited / 1000) + "s");
                                if (attempt >= maxAttempts) {
                                    // 最后一次也超时：降级为阻塞请求
                                    log("已达最大重试次数，降级到阻塞式发送消息");
                                    try {
                                        JSONObject j = new JSONObject();
                                        j.put("message", "降级为阻塞请求：" +
                                            (attemptLatch.getCount() > 0 ? "上次尝试仍未完成" : ""));
                                        writeEventChunk(out, "status", j.toString());
                                    } catch (Exception e) { /* ignore */ }

                                    String fallback = DeepSeekChatBridge.getInstance().sendMessage(message);
                                    stopHeartbeat.set(true);
                                    if (fallback != null && fallback.length() > 0) {
                                        JSONObject j = new JSONObject();
                                        j.put("content", fallback);
                                        j.put("mode", "fallback-blocking");
                                        writeEventChunk(out, "done", j.toString());
                                        endChunked(out);
                                        log("阻塞请求完成：" + fallback.length() + " 字节");
                                        finishedNormally = true;
                                    } else {
                                        JSONObject j = new JSONObject();
                                        j.put("error", "请求超时且降级阻塞也无结果，请检查 DeepSeek 是否正常");
                                        writeEventChunk(out, "error", j.toString());
                                        endChunked(out);
                                    }
                                } else {
                                    // 准备下一次重试
                                    try {
                                        JSONObject j = new JSONObject();
                                        j.put("message", "首次尝试长时间无内容，准备自动重试 (" + (attempt + 1) + "/" + maxAttempts + ")");
                                        writeEventChunk(out, "retrying", j.toString());
                                    } catch (Exception e) { /* ignore */ }
                                }
                            } else {
                                // 正常完成（或者 onError 已被触发）：看是否有错误
                                if (attemptErr.get() != null && attempt >= maxAttempts) {
                                    // 明确错误且是最后一次尝试：关闭流
                                    try { endChunked(out); } catch (Exception e) { /* ignore */ }
                                    finishedNormally = true;
                                } else if (finalReplyRef.get() != null) {
                                    try { endChunked(out); } catch (Exception e) { /* ignore */ }
                                    finishedNormally = true;
                                } else if (attemptErr.get() != null) {
                                    // 前面出现错误但还有重试机会（例如"输入框找不到"可重试），继续
                                    continue;
                                } else {
                                    // gotAnyChunk=true 但是没 done：视为完成
                                    try { endChunked(out); } catch (Exception e) { /* ignore */ }
                                    finishedNormally = true;
                                }
                            }
                        }  // end of while attempt loop

                        if (!finishedNormally) {
                            // 保险：最终失败兜底
                            try {
                                JSONObject j = new JSONObject();
                                j.put("error", "请求未能在限定时间内完成");
                                writeEventChunk(out, "error", j.toString());
                                endChunked(out);
                            } catch (Exception e) { /* ignore */ }
                        }
                        stopHeartbeat.set(true);
                        return;  // 已写入完整响应，跳出后续的统一响应写入
                    }
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
         * 写一个 HTTP chunk（Transfer-Encoding: chunked）。格式: [hex size]\r\n[data]\r\n
         */
        private void writeChunked(OutputStream out, byte[] data) throws IOException {
            if (data == null || data.length == 0) return;
            out.write(Integer.toHexString(data.length).getBytes("UTF-8"));
            out.write("\r\n".getBytes("UTF-8"));
            out.write(data);
            out.write("\r\n".getBytes("UTF-8"));
            out.flush();
        }

        /**
         * 写一个 SSE 事件（包装为 chunk）。格式: event: TYPE\ndata: JSON\n\n
         */
        private void writeEventChunk(OutputStream out, String type, String jsonData) throws IOException {
            String event = "event: " + type + "\n" + "data: " + jsonData + "\n\n";
            writeChunked(out, event.getBytes("UTF-8"));
        }

        /**
         * 结束 chunk 流（最后的 0\r\n\r\n）
         */
        private void endChunked(OutputStream out) throws IOException {
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();
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
