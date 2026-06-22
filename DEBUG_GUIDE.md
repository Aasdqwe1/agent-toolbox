# 调试输出指南 (Debug Output Guide)

本文档说明了所有添加的调试日志标签及其含义，帮助追踪前端-后端通信链路。

## 日志标签 (Log Tags)

### 1. JavaScript Bridge (JSBridge) - 前端↔后端通信

#### 工具调用流程 (Tool Calling Flow)
```
[TOOL_DETECTED]           - 前端检测到工具调用
[TOOL_DETECTED_ARGS]      - 工具参数（首200字符）
[TOOL_DETECTED_DISPATCH]  - 工具事件分发到监听器
[TOOL_EXECUTE_START]      - 开始执行工具
[TOOL_PARSE_ERROR]        - 工具参数解析失败
[TOOL_MANAGER_CALL]       - 调用ToolManager执行工具
[TOOL_RESULT_OBTAINED]    - 工具返回结果
[TOOL_INJECT_START]       - 开始将结果注入回WebView
[TOOL_INJECT_DONE]        - 结果注入完成
[TOOL_INJECT_JS]          - JavaScript注入开始
[TOOL_INJECT_JS_DONE]     - JavaScript注入完成
[TOOL_EXECUTE_ERROR]      - 工具执行发生错误
```

#### DeepSeek 回复流程 (DeepSeek Reply Flow)
```
[DEEPSEEK_REPLY]          - 前端捕获到DeepSeek完整回复
[DEEPSEEK_REPLY_PREVIEW]  - 回复内容预览（首200字符）
[DEEPSEEK_REPLY_DISPATCH] - 回复分发到DeepSeekChatBridge
[DEEPSEEK_CHUNK]          - 流式回复片段
[DEEPSEEK_STATUS]         - DeepSeek状态更新
[DEEPSEEK_ERROR]          - DeepSeek发生错误
[DEEPSEEK_ERROR_DISPATCH] - 错误分发到DeepSeekChatBridge
```

### 2. DeepSeek Chat Bridge - 跨Activity通信

#### 注册流程 (Registration)
```
[REGISTER]                - WebView已注册
[REGISTER]                - WebView对象地址
```

#### 消息发送流程 (Message Sending)
```
[SEND_MESSAGE]            - 启动阻塞式消息发送
[SEND_MESSAGE_WAIT]       - 等待DeepSeek回复
[SEND_MESSAGE_DONE]       - 收到完整回复
[SEND_MESSAGE_TIMEOUT]    - 消息发送超时
[SEND_MESSAGE_ERROR]      - 消息发送错误
[SEND_MESSAGE_ERROR_RESULT] - 返回错误结果
[SEND_MESSAGE_SUCCESS]    - 成功返回回复
[SEND_MESSAGE_INTERRUPTED] - 等待被中断
```

#### 流式消息发送 (Streaming)
```
[SEND_STREAM_START]       - 开始流式消息发送（带requestId）
[SEND_STREAM_INJECT_JS]   - 向WebView注入聊天脚本
[SEND_STREAM_WAITING]     - 等待JavaScript端响应
[SEND_STREAM_JS_DONE]     - 收到JavaScript完成通知
[SEND_STREAM_JS_ERROR]    - JavaScript端报错
[SEND_STREAM_TIMEOUT]     - 流式等待超时
[SEND_STREAM_NO_REPLY]    - 未收到回复
[SEND_STREAM_CLEANUP]     - 清理请求资源
```

#### 回调处理 (Callback Handling)
```
[ON_CHUNK]                - 接收流式片段
[ON_CHUNK_ERROR]          - 片段回调异常
[ON_REPLY]                - 接收完整回复
[ON_STATUS]               - 接收状态更新
[ON_ERROR]                - 接收错误信息
```

### 3. 工具管理器 (ToolManager)

```
[CALL_TOOL]               - 调用工具（工具名）
[CALL_TOOL_ARGS]          - 工具参数（首200字符）
[CALL_TOOL_NOT_FOUND]     - 工具不存在
[TOOL_EXECUTE_START]      - 工具执行开始
[TOOL_EXECUTE_END]        - 工具执行完成（输出长度）
[TOOL_EXECUTE_ERROR]      - 工具执行异常
[CALL_TOOL_RESULT]        - 工具返回结果（首200字符）
```

### 4. MCP 服务器 (McpServer)

#### HTTP请求处理 (Request Processing)
```
[HTTP_REQUEST]            - 收到HTTP请求（路径和体长度）
[HTTP_REQUEST_BODY]       - 请求体内容（前500字符）
[HTTP_REQUEST_EMPTY]      - 空请求（心跳包）
[HTTP_CHAT_REQUEST]       - 聊天API请求
[HTTP_REQUEST_EMPTY_JSON] - 空JSON对象请求
[HTTP_JSON_RPC_REQUEST]   - 处理JSON-RPC请求
[HTTP_JSON_RPC_RESPONSE]  - 发送JSON-RPC响应
[HTTP_RESPONSE_SENT]      - 响应已发送
```

### 5. 主Activity (MainActivity)

#### 服务生命周期 (Service Lifecycle)
```
[SERVER_START]            - 启动MCP服务（端口号）
[SERVER_START_SUCCESS]    - 服务启动成功（监听地址）
[SERVER_START_ERROR]      - 服务启动失败
[SERVER_STOP]             - 停止MCP服务
[SERVER_STOP_SUCCESS]     - 服务已停止
[OPEN_DEEPSEEK]           - 打开DeepSeek Activity
[OPEN_DEEPSEEK_STARTED]   - DeepSeek Activity已启动
```

### 6. JavaScript 前端 (Frontend JavaScript)

```
[MCP-Frontend]            - 前端日志消息
[MCP-Frontend] Checking message - 检查消息中的工具调用
[MCP-Frontend] Tool call detected - 工具调用检测成功
[MCP-Frontend] Tool result received - 工具结果已收到
[MCP-Frontend] JSON detected but not a tool call - 检测到JSON但不是工具调用
```

## 通信链路追踪示例 (Communication Flow Examples)

### 场景1: 完整的工具调用链路 (Complete Tool Call Chain)

```
前端 → 后端：
1. [JSBridge] [TOOL_DETECTED] toolName=HttpRequestTool
2. [JSBridge] [TOOL_DETECTED_ARGS] {...args...}
3. [JSBridge] [TOOL_DETECTED_DISPATCH] 
4. [JSBridge] [TOOL_EXECUTE_START] Starting tool execution: HttpRequestTool
5. [JSBridge] [TOOL_MANAGER_CALL] Calling ToolManager.callTool(HttpRequestTool)
6. [ToolManager] [CALL_TOOL] name=HttpRequestTool
7. [ToolManager] [TOOL_EXECUTE_START] Executing HttpRequestTool
8. [ToolManager] [TOOL_EXECUTE_END] Completed HttpRequestTool, output length=1234
9. [JSBridge] [TOOL_RESULT_OBTAINED] HttpRequestTool returned 1234 bytes
10. [JSBridge] [TOOL_INJECT_START] Injecting tool result back to WebView
11. [JSBridge] [TOOL_INJECT_JS] Starting JavaScript injection
12. [JSBridge] [TOOL_INJECT_DONE] Tool result injected
```

### 场景2: HTTP API请求和DeepSeek回复 (HTTP API Request & DeepSeek Reply)

```
客户端 → 服务器 → DeepSeek → 客户端：
1. [McpServer] [HTTP_REQUEST] path=/api/messages method=POST
2. [McpServer] [HTTP_REQUEST_BODY] {...request...}
3. [McpServer] [HTTP_JSON_RPC_REQUEST] Processing JSON-RPC request
4. [DeepSeekChatBridge] [SEND_STREAM_START] requestId=req_123_xyz msgLen=500
5. [DeepSeekChatBridge] [SEND_STREAM_INJECT_JS] Injecting chat script into WebView
6. [DeepSeekChatBridge] [SEND_STREAM_WAITING] requestId=req_123_xyz waiting for response...
7. [JSBridge] [DEEPSEEK_CHUNK] requestId=req_123_xyz chunkLen=100
8. [DeepSeekChatBridge] [ON_CHUNK] requestId=req_123_xyz chunkLen=100
9. [JSBridge] [DEEPSEEK_REPLY] requestId=req_123_xyz replyLen=2000
10. [DeepSeekChatBridge] [ON_REPLY] requestId=req_123_xyz replyLen=2000
11. [DeepSeekChatBridge] [SEND_STREAM_JS_DONE] requestId=req_123_xyz replyLen=2000
12. [DeepSeekChatBridge] [SEND_STREAM_CLEANUP] requestId=req_123_xyz cleaned up
13. [McpServer] [HTTP_JSON_RPC_RESPONSE] Sending response, len=2100
14. [McpServer] [HTTP_RESPONSE_SENT] Response sent for /api/messages
```

### 场景3: 错误处理链路 (Error Handling Flow)

```
错误检测与处理：
1. [ToolManager] [CALL_TOOL] name=InvalidTool
2. [ToolManager] [CALL_TOOL_NOT_FOUND] Tool not found: InvalidTool
3. [JSBridge] [TOOL_INJECT_START] Injecting error result
4. [JSBridge] [TOOL_INJECT_DONE] Tool result injected

或：
1. [McpServer] [HTTP_REQUEST] path=/api/chat
2. [McpServer] [HTTP_REQUEST_EMPTY] Empty request (heartbeat)
3. [DeepSeekChatBridge] [SEND_STREAM_TIMEOUT] requestId=req_456_xyz
4. [DeepSeekChatBridge] [ON_ERROR] requestId=req_456_xyz error=timeout
```

## 日志过滤和查看 (Filtering & Viewing Logs)

### 使用 adb logcat 查看日志

```bash
# 查看所有MCP相关的日志
adb logcat | grep -E "JSBridge|DeepSeekChatBridge|ToolManager|McpServer|MainActivity"

# 查看特定模块的日志
adb logcat | grep "DeepSeekChatBridge"

# 查看特定标签的日志
adb logcat | grep "TOOL_DETECTED"

# 保存日志到文件
adb logcat | tee debug.log

# 清空日志缓冲区
adb logcat -c

# 查看实时日志（从现在开始）
adb logcat -v threadtime | grep -E "JSBridge|DeepSeekChatBridge"
```

## 常见问题诊断 (Common Issues Diagnosis)

### 问题1: 前端检测不到工具调用 (Tool Call Not Detected)

寻找以下日志序列：
- 应该有 `[TOOL_DETECTED]` 日志
- 如果没有，检查是否有 `[MCP-Frontend] Checking message` 日志
- 检查 `[MCP-Frontend] JSON detected but not a tool call` 是否出现

### 问题2: 工具执行后没有返回结果 (Tool Result Not Returned)

寻找以下日志序列：
- 有 `[TOOL_EXECUTE_END]` 但没有 `[TOOL_INJECT_DONE]`
- 检查 `[TOOL_INJECT_START]` 是否出现
- 查看是否有 `[JSBridge] [TOOL_INJECT_ERROR]` 错误

### 问题3: DeepSeek回复超时 (DeepSeek Reply Timeout)

寻找以下日志序列：
- 有 `[SEND_STREAM_WAITING]` 但超过3600秒没有 `[ON_REPLY]`
- 检查是否有 `[SEND_STREAM_TIMEOUT]` 日志
- 查看 `[DeepSeekChatBridge] [SEND_STREAM_JS_ERROR]` 是否有具体错误信息

### 问题4: HTTP请求返回错误 (HTTP Request Error)

寻找以下日志序列：
- `[HTTP_REQUEST]` 收到请求但后面没有 `[HTTP_RESPONSE_SENT]`
- 检查是否有 `[HTTP_REQUEST_EMPTY_JSON]` 错误
- 查看 `[McpServer]` 的具体错误信息

## 日志级别说明 (Log Levels)

- **DEBUG (d)**: 详细的调试信息，正常流程的每一步都会记录
- **INFO (i)**: 重要的流程节点（启动、停止、主要事件）
- **WARN (w)**: 可能的问题（超时、空请求、权限问题）
- **ERROR (e)**: 错误情况（异常、失败的操作）

## 性能考虑 (Performance Notes)

- 日志输出会对性能有一定影响，特别是大量流式数据时
- 生产环境建议降低日志级别或关闭不必要的日志标签
- 当需要详细追踪时，建议使用 `adb logcat > debug.log` 保存日志供后续分析

---

**更新时间**: 2026-06-22
**版本**: 1.0
