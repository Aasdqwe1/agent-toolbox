package com.example.agenttoolbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.example.agenttoolbox.tools.ToolManager;

import org.json.JSONObject;

/**
 * JavaScript 接口 - 用于网页和原生代码通信
 * 功能：
 * - 接收网页端检测到的工具调用
 * - 执行工具并返回结果
 */
public class JavaScriptBridge {
    
    private Context context;
    private WebView webView;
    private Handler handler;
    private OnToolCallListener listener;
    
    public interface OnToolCallListener {
        void onToolCallDetected(String toolName, String arguments);
        void onToolResult(String toolName, String result);
        void onPageHtmlExtracted(String html, boolean success, String error);
    }
    
    public JavaScriptBridge(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    public void setOnToolCallListener(OnToolCallListener listener) {
        this.listener = listener;
    }
    
    /**
     * JS 调用：检测到工具调用
     */
    @JavascriptInterface
    public void onToolCallDetected(final String toolName, final String argumentsJson) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onToolCallDetected(toolName, argumentsJson);
                }
                
                // 自动执行工具
                executeTool(toolName, argumentsJson);
            }
        });
    }
    
    /**
     * JS 调用：DeepSeek AI 回复已捕获（由 WebView 注入的 JS 调用）
     * 新格式：带 requestId 路由到对应请求，避免多会话消息错乱
     */
    @JavascriptInterface
    public void onDeepSeekReply(final String requestIdOrReply, final String replyOrNull) {
        final String requestId;
        final String reply;
        // 兼容：如果只传了一个参数，第二个参数为 null → 按旧格式处理
        if (replyOrNull == null || replyOrNull.isEmpty()) {
            requestId = null;
            reply = requestIdOrReply;
        } else {
            requestId = requestIdOrReply;
            reply = replyOrNull;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                DeepSeekChatBridge.getInstance().onDeepSeekReply(requestId, reply);
            }
        });
    }

    /**
     * JS 调用：DeepSeek 流式回复的中间片段（每 500ms 触发一次）
     * 新格式：带 requestId 路由
     */
    @JavascriptInterface
    public void onDeepSeekChunk(final String requestIdOrChunk, final String chunkOrNull) {
        final String requestId;
        final String chunk;
        if (chunkOrNull == null || chunkOrNull.isEmpty()) {
            requestId = null;
            chunk = requestIdOrChunk;
        } else {
            requestId = requestIdOrChunk;
            chunk = chunkOrNull;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                DeepSeekChatBridge.getInstance().onDeepSeekChunk(requestId, chunk);
            }
        });
    }

    /**
     * JS 调用：DeepSeek 聊天过程发生错误
     * 新格式：带 requestId 路由
     */
    @JavascriptInterface
    public void onDeepSeekError(final String requestIdOrError, final String errorOrNull) {
        final String requestId;
        final String error;
        if (errorOrNull == null || errorOrNull.isEmpty()) {
            requestId = null;
            error = requestIdOrError;
        } else {
            requestId = requestIdOrError;
            error = errorOrNull;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                DeepSeekChatBridge.getInstance().onDeepSeekError(requestId, error);
            }
        });
    }

    /**
     * JS 调用：DeepSeek 聊天过程中的状态更新（生成中、等待输入框等）
     * 用于流式传输期间向 HTTP 客户端发送心跳事件，避免连接超时误判
     */
    @JavascriptInterface
    public void onDeepSeekStatus(final String requestIdOrStatus, final String statusOrNull) {
        final String requestId;
        final String status;
        if (statusOrNull == null || statusOrNull.isEmpty()) {
            requestId = null;
            status = requestIdOrStatus;
        } else {
            requestId = requestIdOrStatus;
            status = statusOrNull;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                DeepSeekChatBridge.getInstance().onDeepSeekStatus(requestId, status);
            }
        });
    }

    /**
     * JS 调用：日志输出
     */
    @JavascriptInterface
    public void log(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                android.util.Log.d("JSBridge", message);
            }
        });
    }

    /**
     * JS 调用：页面源码提取完成
     */
    @JavascriptInterface
    public void onPageHtmlExtracted(final String html, final boolean success, final String error) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onPageHtmlExtracted(html, success, error);
                }
            }
        });
    }
    
    /**
     * 执行工具调用
     */
    private void executeTool(final String toolName, final String argumentsJson) {
        try {
            // 解析参数
            JSONObject arguments;
            try {
                arguments = new JSONObject(argumentsJson);
            } catch (Exception e) {
                arguments = new JSONObject();
            }
            
            // 调用工具管理器执行
            JSONObject result = ToolManager.getInstance().callTool(toolName, arguments);
            
            // 把结果注入回网页
            final String resultJson = result.toString();
            
            handler.post(new Runnable() {
                @Override
                public void run() {
                    injectToolResult(resultJson);
                    
                    if (listener != null) {
                        listener.onToolResult(toolName, resultJson);
                    }
                }
            });
            
        } catch (Exception e) {
            final String errorMsg = "工具执行失败: " + e.getMessage();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    
    /**
     * 把工具执行结果注入回网页
     */
    private void injectToolResult(String resultJson) {
        String js = "(function() {" +
            "  // 创建一个隐藏的结果容器" +
            "  var resultContainer = document.getElementById('mcp-tool-result');" +
            "  if (!resultContainer) {" +
            "    resultContainer = document.createElement('div');" +
            "    resultContainer.id = 'mcp-tool-result';" +
            "    resultContainer.style.display = 'none';" +
            "    document.body.appendChild(resultContainer);" +
            "  }" +
            "  resultContainer.textContent = '" + escapeJsString(resultJson) + "';" +
            "  " +
            "  // 触发自定义事件，通知页面有新结果" +
            "  var event = new CustomEvent('mcp-tool-result', {" +
            "    detail: { result: " + resultJson + " }" +
            "  });" +
            "  document.dispatchEvent(event);" +
            "  " +
            "  // 尝试找到输入框并填入结果（辅助用户粘贴）" +
            "  tryToInjectResult(" + resultJson + ");" +
            "})()";
        
        webView.evaluateJavascript(js, null);
    }
    
    /**
     * 转义 JS 字符串中的特殊字符
     */
    private String escapeJsString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * 注入监听脚本到网页
     */
    public void injectObserverScript() {
        String js = getObserverScript();
        webView.evaluateJavascript(js, null);
    }
    
    /**
     * 获取 DOM 监听脚本
     */
    private String getObserverScript() {
        return "(function() {" +
            "  if (window.__mcpObserverInjected) return;" +
            "  window.__mcpObserverInjected = true;" +
            "  " +
            "  Android.log('MCP Observer 脚本已注入');" +
            "  " +
            "  // 存储已处理的消息，避免重复处理" +
            "  var processedMessages = new Set();" +
            "  " +
            "  // 从文本中提取 JSON" +
            "  function extractJsonFromText(text) {" +
            "    if (!text) return null;" +
            "    " +
            "    // 尝试直接解析" +
            "    try {" +
            "      var obj = JSON.parse(text);" +
            "      if (obj && typeof obj === 'object') return obj;" +
            "    } catch(e) {}" +
            "    " +
            "    // 尝试提取代码块中的 JSON" +
            "    var codeBlockMatch = text.match(/```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```/i);" +
            "    if (codeBlockMatch) {" +
            "      try {" +
            "        return JSON.parse(codeBlockMatch[1]);" +
            "      } catch(e) {}" +
            "    }" +
            "    " +
            "    // 尝试提取第一个 { 到最后一个 }" +
            "    var firstBrace = text.indexOf('{');" +
            "    var lastBrace = text.lastIndexOf('}');" +
            "    if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {" +
            "      try {" +
            "        return JSON.parse(text.substring(firstBrace, lastBrace + 1));" +
            "      } catch(e) {}" +
            "    }" +
            "    " +
            "    return null;" +
            "  }" +
            "  " +
            "  // 解析工具调用" +
            "  function parseToolCall(obj) {" +
            "    if (!obj) return null;" +
            "    " +
            "    // 格式1: JSON-RPC 格式 { method: 'tools/call', params: { name: '...', arguments: {...} } }" +
            "    if (obj.method === 'tools/call' && obj.params) {" +
            "      return { name: obj.params.name, arguments: obj.params.arguments || {} };" +
            "    }" +
            "    " +
            "    // 格式2: { name: 'tool', arguments: {...} }" +
            "    if (obj.name && obj.arguments) {" +
            "      return { name: obj.name, arguments: obj.arguments };" +
            "    }" +
            "    " +
            "    // 格式3: { tool: 'tool', args: {...} }" +
            "    if (obj.tool && obj.args) {" +
            "      return { name: obj.tool, arguments: obj.args };" +
            "    }" +
            "    " +
            "    // 格式4: { tool_name: 'tool', params: {...} }" +
            "    if (obj.tool_name && obj.params) {" +
            "      return { name: obj.tool_name, arguments: obj.params };" +
            "    }" +
            "    " +
            "    return null;" +
            "  }" +
            "  " +
            "  // 检查消息是否包含工具调用" +
            "  function checkMessageForToolCall(messageEl) {" +
            "    if (!messageEl) return;" +
            "    " +
            "    // 用元素的文本内容作为唯一标识" +
            "    var text = messageEl.innerText || messageEl.textContent || '';" +
            "    if (!text || text.length < 10) return;" +
            "    " +
            "    // 生成简单的哈希作为 ID" +
            "    var hash = 0;" +
            "    for (var i = 0; i < Math.min(text.length, 200); i++) {" +
            "      hash = ((hash << 5) - hash) + text.charCodeAt(i);" +
            "      hash |= 0;" +
            "    }" +
            "    var msgId = hash.toString();" +
            "    " +
            "    if (processedMessages.has(msgId)) return;" +
            "    processedMessages.add(msgId);" +
            "    " +
            "    // 提取并解析工具调用" +
            "    var jsonObj = extractJsonFromText(text);" +
            "    var toolCall = parseToolCall(jsonObj);" +
            "    " +
            "    if (toolCall && toolCall.name) {" +
            "      Android.log('检测到工具调用: ' + toolCall.name);" +
            "      Android.onToolCallDetected(toolCall.name, JSON.stringify(toolCall.arguments));" +
            "      " +
            "      // 给消息添加标记，显示正在执行" +
            "      messageEl.__mcpProcessing = true;" +
            "      showToolCallStatus(messageEl, '⏳ 工具执行中...');" +
            "    }" +
            "  }" +
            "  " +
            "  // 显示工具调用状态" +
            "  function showToolCallStatus(messageEl, statusText) {" +
            "    // 查找或创建状态标签" +
            "    var statusEl = messageEl.querySelector('.mcp-tool-status');" +
            "    if (!statusEl) {" +
            "      statusEl = document.createElement('div');" +
            "      statusEl.className = 'mcp-tool-status';" +
            "      statusEl.style.cssText = 'margin-top: 8px; padding: 8px 12px; background: #f0f7ff; " +
            "        border-radius: 8px; font-size: 13px; color: #3964fe; border: 1px solid #d6e4ff;';" +
            "      messageEl.appendChild(statusEl);" +
            "    }" +
            "    statusEl.textContent = statusText;" +
            "  }" +
            "  " +
            "  // 尝试把结果注入到输入框" +
            "  function tryToInjectResult(result) {" +
            "    // 尝试找到 textarea 或 contenteditable 输入框" +
            "    var input = document.querySelector('textarea') || " +
            "                document.querySelector('[contenteditable=\"true\"]');" +
            "    if (input) {" +
            "      // 不自动填入，只显示提示，避免干扰用户" +
            "      Android.log('找到输入框，可以粘贴结果');" +
            "    }" +
            "  }" +
            "  " +
            "  // 扫描所有消息" +
            "  function scanAllMessages() {" +
            "    // 尝试多种选择器来找到消息元素" +
            "    var selectors = [" +
            "      '.message', '.chat-message', '.msg'," +
            "      '[data-testid*=\"message\"]'," +
            "      '.prose', '.markdown-body'" +
            "    ];" +
            "    " +
            "    for (var i = 0; i < selectors.length; i++) {" +
            "      var messages = document.querySelectorAll(selectors[i]);" +
            "      if (messages && messages.length > 0) {" +
            "        messages.forEach(function(msg) {" +
            "          checkMessageForToolCall(msg);" +
            "        });" +
            "      }" +
            "    }" +
            "  }" +
            "  " +
            "  // 创建 MutationObserver 监听 DOM 变化" +
            "  var observer = new MutationObserver(function(mutations) {" +
            "    mutations.forEach(function(mutation) {" +
            "      if (mutation.addedNodes && mutation.addedNodes.length > 0) {" +
            "        mutation.addedNodes.forEach(function(node) {" +
            "          if (node.nodeType === 1) { // 元素节点" +
            "            // 检查新增的节点本身" +
            "            checkMessageForToolCall(node);" +
            "            // 检查节点内部的消息" +
            "            var messages = node.querySelectorAll && node.querySelectorAll('.message, .chat-message, .msg, [data-testid*=\"message\"]');" +
            "            if (messages) {" +
            "              messages.forEach(function(msg) {" +
            "                checkMessageForToolCall(msg);" +
            "              });" +
            "            }" +
            "          }" +
            "        });" +
            "      }" +
            "    });" +
            "  });" +
            "  " +
            "  // 开始监听" +
            "  function startObserving() {" +
            "    var target = document.body || document.getElementById('root');" +
            "    if (target) {" +
            "      observer.observe(target, {" +
            "        childList: true," +
            "        subtree: true," +
            "        characterData: true" +
            "      });" +
            "      Android.log('MCP Observer 已开始监听 DOM 变化');" +
            "      " +
            "      // 先扫描一遍已有消息" +
            "      setTimeout(scanAllMessages, 1000);" +
            "    } else {" +
            "      setTimeout(startObserving, 500);" +
            "    }" +
            "  }" +
            "  " +
            "  // 监听工具结果事件，更新状态显示" +
            "  document.addEventListener('mcp-tool-result', function(e) {" +
            "    var result = e.detail && e.detail.result;" +
            "    if (result) {" +
            "      // 找到正在处理的消息，更新状态" +
            "      var processingEl = document.querySelector('[__mcpProcessing=\"true\"]');" +
            "      if (!processingEl) {" +
            "        // 尝试找带状态标签的消息" +
            "        var statusEls = document.querySelectorAll('.mcp-tool-status');" +
            "        if (statusEls.length > 0) {" +
            "          var lastStatus = statusEls[statusEls.length - 1];" +
            "          processingEl = lastStatus.parentElement;" +
            "        }" +
            "      }" +
            "      " +
            "      if (processingEl) {" +
            "        var resultText = result.content && result.content[0] && result.content[0].text" +
            "          ? result.content[0].text" +
            "          : JSON.stringify(result, null, 2);" +
            "        " +
            "        showToolCallStatus(processingEl, '✅ 工具执行完成');" +
            "        " +
            "        // 添加结果显示区域" +
            "        var resultBox = processingEl.querySelector('.mcp-tool-result-box');" +
            "        if (!resultBox) {" +
            "          resultBox = document.createElement('div');" +
            "          resultBox.className = 'mcp-tool-result-box';" +
            "          resultBox.style.cssText = 'margin-top: 8px; padding: 12px; " +
            "            background: #f8fff9; border-radius: 8px; font-size: 12px; " +
            "            font-family: monospace; white-space: pre-wrap; " +
            "            border: 1px solid #d4edda; max-height: 200px; overflow-y: auto;';" +
            "          processingEl.appendChild(resultBox);" +
            "        }" +
            "        resultBox.textContent = resultText;" +
            "        " +
            "        processingEl.__mcpProcessing = false;" +
            "      }" +
            "    }" +
            "  });" +
            "  " +
            "  // 启动" +
            "  startObserving();" +
            "  " +
            "  // 兜底轮询：每2秒扫描一次消息，防止MutationObserver在后台被挂起" +
            "  // 特别是悬浮窗小窗模式下，WebView可能被系统优化导致Observer不触发" +
            "  setInterval(function() {" +
            "    scanAllMessages();" +
            "  }, 2000);" +
            "})()";
    }
}
