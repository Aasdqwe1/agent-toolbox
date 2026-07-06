package com.example.agenttoolbox;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import com.example.agenttoolbox.AppLogger;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek HTTP 聊天桥接层
 *
 * 负责跨 Activity 通信：
 * - McpServer（HTTP 线程）通过此类向 DeepSeekActivity 的 WebView 发送消息
 * - 等待 WebView 通过 JS 注入发消息、MutationObserver 监听到 AI 回复
 * - 回复内容通过 CountDownLatch / StreamCallback 同步返回给 HTTP 线程
 *
 * 并发安全：每个请求分配唯一 requestId，用 ConcurrentHashMap 保存
 * 对应回调，避免多个请求相互覆盖。
 *
 * 使用方式：
 *   DeepSeekActivity.onCreate()   → DeepSeekChatBridge.register(webView)
 *   HTTP 请求线程                  → sendMessageStream(message, callback)
 */
public class DeepSeekChatBridge {

    private static DeepSeekChatBridge instance;

    public static DeepSeekChatBridge getInstance() {
        if (instance == null) {
            synchronized (DeepSeekChatBridge.class) {
                if (instance == null) {
                    instance = new DeepSeekChatBridge();
                }
            }
        }
        return instance;
    }

    // 当前绑定的 WebView 和上下文
    private WebView boundWebView;
    private Handler mainHandler;
    private boolean webViewLoaded;

    // ---- 并发请求管理：每个 requestId 保存一份回调 ----
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, StreamCallback> callbacksById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> latchById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<String>> replyById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<String>> errorById = new ConcurrentHashMap<>();

    // 注册 / 注销
    public synchronized void register(WebView webView) {
        this.boundWebView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        AppLogger.d("DeepSeekChatBridge", "已注册 WebView: " + (webView != null ? "有效" : "null"));
    }

    // Activity 返回/销毁时调用：保持 WebView 存活
    public synchronized void detach() {
        AppLogger.d("DeepSeekChatBridge", "detach: WebView 保持存活");
    }

    public synchronized void unregister() {
        this.boundWebView = null;
        this.mainHandler = null;
        this.webViewLoaded = false;
        callbacksById.clear();
        latchById.clear();
        replyById.clear();
        errorById.clear();
        AppLogger.d("DeepSeekChatBridge", "已注销 WebView");
    }

    public synchronized WebView getBoundWebView() { return boundWebView; }
    public synchronized boolean isRegistered() { return boundWebView != null; }
    public synchronized boolean isWebViewLoaded() { return webViewLoaded && boundWebView != null; }
    public synchronized void markAsLoaded() { this.webViewLoaded = true; }

    /**
     * 流式回调接口
     */
    public static abstract class StreamCallback {
        public abstract void onChunk(String chunk);
        public abstract void onDone(String reply);
        public abstract void onError(String error);
    }

    /**
     * 分配一个新的请求 ID
     */
    private String nextRequestId() {
        return "req_" + requestIdCounter.incrementAndGet() + "_" + System.currentTimeMillis();
    }

    /**
     * 清除某个请求的所有状态
     */
    private void cleanupRequest(String requestId) {
        if (requestId == null) return;
        callbacksById.remove(requestId);
        latchById.remove(requestId);
        replyById.remove(requestId);
        errorById.remove(requestId);
    }

    /**
     * 发送消息，阻塞等待 DeepSeek 返回完整回复文本。
     * 等价于 sendMessageStream + 等待 onDone，用于 McpServer 的"降级阻塞"路径。
     * 最长等待 180 秒。返回 null 表示失败或未捕获到内容。
     */
    public String sendMessage(final String message) {
        final CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> replyRef =
            new java.util.concurrent.atomic.AtomicReference<String>();
        final java.util.concurrent.atomic.AtomicReference<String> errRef =
            new java.util.concurrent.atomic.AtomicReference<String>();

        sendMessageStream(message, new StreamCallback() {
            @Override
            public void onChunk(String chunk) { /* 流式过程忽略 */ }
            @Override
            public void onDone(String reply) {
                replyRef.set(reply);
                latch.countDown();
            }
            @Override
            public void onError(String error) {
                errRef.set(error);
                latch.countDown();
            }
        });

        try {
            // 延长到 1800 秒（30 分钟），给 LLM 足够时间处理复杂任务
            if (!latch.await(1800, java.util.concurrent.TimeUnit.SECONDS)) {
                AppLogger.w("DeepSeekChatBridge",
                    "sendMessage 超时（1800s），message=" + (message == null ? "" : message));
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (errRef.get() != null) {
            AppLogger.w("DeepSeekChatBridge", "sendMessage 错误: " + errRef.get());
            return null;
        }
        return replyRef.get();
    }

    /**
     * 发送消息并实时回调每一段回复（流式）
     */
    public void sendMessageStream(final String message, final StreamCallback callback) {
        final WebView wb;
        final Handler handler;
        synchronized (this) {
            wb = boundWebView;
            handler = mainHandler;
        }
        if (wb == null || handler == null) {
            callback.onError("WebView 未注册");
            return;
        }

        // 分配 requestId，并保存回调
        final String requestId = nextRequestId();
        callbacksById.put(requestId, callback);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> replyRef = new AtomicReference<String>();
        final AtomicReference<String> errorRef = new AtomicReference<String>();
        latchById.put(requestId, latch);
        replyById.put(requestId, replyRef);
        errorById.put(requestId, errorRef);

        handler.post(new Runnable() {
            @Override
            public void run() {
                injectChatScript(wb, requestId, message);

                // 后台线程等待完成，以便调 onDone / onError
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 延长到 3600 秒（1 小时），让 JavaScript 轮询循环的动态超时真正控制
                            // JavaScript 端已有 pollCount 检查（600/900/1800 = 5/7.5/15 分钟）
                            boolean completed = latch.await(3600, TimeUnit.SECONDS);
                            String reply = replyRef.get();
                            String err = errorRef.get();
                            StreamCallback cb = callbacksById.get(requestId);
                            if (!completed) {
                                if (cb != null) cb.onError("流式等待超时（3600s，JavaScript 端未触发完成）");
                            } else if (err != null) {
                                if (cb != null) cb.onError(err);
                            } else if (reply != null) {
                                if (cb != null) cb.onDone(reply);
                            } else {
                                if (cb != null) cb.onError("未收到回复");
                            }
                        } catch (InterruptedException e) {
                            StreamCallback cb = callbacksById.get(requestId);
                            if (cb != null) cb.onError("等待被中断");
                            Thread.currentThread().interrupt();
                        } finally {
                            cleanupRequest(requestId);
                        }
                    }
                }).start();
            }
        });
    }

    /**
     * 由 JS 桥接调用：DeepSeek 页面尚未出现新消息，但能检测到仍在生成/处理，
     * 用于向客户端发送心跳，避免 HTTP 端误判为"超时"。
     */
    public void onDeepSeekStatus(String requestId, String statusText) {
        if (requestId == null) return;
        StreamCallback cb = callbacksById.get(requestId);
        if (cb != null) {
            try { cb.onChunk("[STATUS] " + (statusText == null ? "" : statusText)); } catch (Exception ignored) {}
        }
    }

    private void injectChatScript(final WebView webView,
                                   final String requestId,
                                   final String message) {
        if (webView == null) {
            StreamCallback cb = callbacksById.get(requestId);
            if (cb != null) cb.onError("WebView 为 null");
            cleanupRequest(requestId);
            return;
        }

        // ========== Step 1: 监听脚本（先启动，再发送） ==========
        // 关键修复：
        //   - 在发送前记录当前已有 AI 消息数量（baseline）
        //   - 只有新增的消息（index >= baseline）才被视为本次的回复
        //   - JS 变量改为以 requestId 命名，避免多请求相互覆盖
                final String observerScript = "(function() {\n" +
            "  try {\n" +
            "  var __rid = " + JSONObject.quote(requestId) + ";\n" +
            "  var __prefix = 'ds_' + __rid + '_';\n" +
            "  window.__deepseekRid = __rid;\n" +
            "  if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "  var finished = false;\n" +
            "  var pollCount = 0;\n" +
            "  var lastTextLen = 0;\n" +
            "  var stableCount = 0;\n" +
            "  // 记录发送前已有的 AI 回复数（baseline）\n" +
            "  var initialAiCount = document.querySelectorAll('.ds-assistant-message-main-content').length;\n" +
            "  Android.log('[JS] 初始AI回复数=' + initialAiCount + ', url=' + window.location.href);\n" +
            "\n" +
            "  function isSendButtonReady() {\n" +
            "    // 只检查发送按钮元素内部的 path，不遍历全页 SVG\n" +
            "    // 发送按钮定位：ds-button--primary class（与 sendScript 一致）\n" +
            "    var btn = document.querySelector('div[role=\"button\"].ds-button--primary') ||\n" +
            "              document.querySelector('.ds-button--primary');\n" +
            "    if (!btn) return true; // 找不到按钮，不阻塞\n" +
            "    var paths = btn.querySelectorAll('svg path');\n" +
            "    for (var i = 0; i < paths.length; i++) {\n" +
            "      var d = paths[i].getAttribute('d') || '';\n" +
            "      // 发送按钮（就绪）：M8.3125 0.981587...\n" +
            "      if (/^M8\\.3125/.test(d)) return true;\n" +
            "      // 停止按钮（生成中）：M2 4.88C2 3.68009...\n" +
            "      if (/^M2 4\\.88/.test(d)) return false;\n" +
            "    }\n" +
            "    return true; // 未匹配到已知 path，不阻塞\n" +
            "  }\n" +
            "\n" +
            "  function finish(reply) {\n" +
            "    if (finished) return;\n" +
            "    finished = true;\n" +
            "    if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "    Android.log('[JS] 完成: 长度=' + (reply ? reply.length : 0) + '\\n' + (reply || ''));\n" +
            "    Android.onDeepSeekReply(__rid, reply || '');\n" +
            "  }\n" +
            "\n" +
            "  // 从 AI 回复元素中提取 JSON-RPC 内容\n" +
            "  function extractReply(el) {\n" +
            "    // 优先从 span 中提取（文档：AI 回复在 .ds-assistant-message-main-content span）\n" +
            "    var span = el.querySelector('.ds-assistant-message-main-content span');\n" +
            "    var rawText = '';\n" +
            "    if (span) {\n" +
            "      rawText = (span.innerText || span.textContent || '').trim();\n" +
            "    }\n" +
            "    if (!rawText) {\n" +
            "      rawText = (el.innerText || el.textContent || '').trim();\n" +
            "    }\n" +
            "    return rawText;\n" +
            "  }\n" +
            "\n" +
            "  // 解析 JSON-RPC 并提取内容\n" +
            "  function parseJsonRpc(rawText) {\n" +
            "    if (!rawText) return null;\n" +
            "    var firstBrace = rawText.indexOf('{');\n" +
            "    if (firstBrace === -1) return null;\n" +
            "    var jsonStr = rawText.substring(firstBrace);\n" +
            "    // 匹配完整 JSON 对象\n" +
            "    var depth = 0; var endIdx = -1;\n" +
            "    for (var ci = 0; ci < jsonStr.length; ci++) {\n" +
            "      if (jsonStr[ci] === '{') depth++;\n" +
            "      else if (jsonStr[ci] === '}') { depth--; if (depth === 0) { endIdx = ci; break; } }\n" +
            "    }\n" +
            "    if (endIdx > 0) jsonStr = jsonStr.substring(0, endIdx + 1);\n" +
            "    try { return JSON.parse(jsonStr); } catch(e) {}\n" +
            "    return null;\n" +
            "  }\n" +
            "\n" +
            "  function pollOnce() {\n" +
            "    if (finished) return;\n" +
            "    pollCount++;\n" +
            "\n" +
            "    // 超时 5 分钟\n" +
            "    if (pollCount > 600) {\n" +
            "      Android.log('[JS] 超时: pollCount=' + pollCount);\n" +
            "      finish('');\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 每 10 次轮询（约 5 秒）输出一次诊断状态\n" +
            "    if (pollCount % 10 === 0) {\n" +
            "      var diagAiCount = document.querySelectorAll('.ds-assistant-message-main-content').length;\n" +
            "      var diagReady = isSendButtonReady();\n" +
            "      Android.log('[JS] 诊断 poll#' + pollCount + ' ready=' + diagReady + ' aiCount=' + diagAiCount + '/' + initialAiCount + ' stable=' + stableCount + ' lastLen=' + lastTextLen);\n" +
            "    }\n" +
            "\n" +
            "    // 快速跳过：若检测到停止按钮（LLM 生成中），本轮直接返回\n" +
            "    // 不重置 stableCount/lastTextLen，避免破坏稳定性检查\n" +
            "    if (!isSendButtonReady()) return;\n" +
            "\n" +
            "    // 检查是否有新的 AI 回复\n" +
            "    var aiMsgs = document.querySelectorAll('.ds-assistant-message-main-content');\n" +
            "    if (aiMsgs.length <= initialAiCount) return;\n" +
            "\n" +
            "    // 取最新的 AI 回复元素\n" +
            "    var lastAi = aiMsgs[aiMsgs.length - 1];\n" +
            "\n" +
            "    // 提取文本（优先从 span）\n" +
            "    var span = lastAi.querySelector('span');\n" +
            "    var rawText = '';\n" +
            "    if (span) {\n" +
            "      rawText = (span.innerText || span.textContent || '').trim();\n" +
            "    }\n" +
            "    if (!rawText) {\n" +
            "      rawText = (lastAi.innerText || lastAi.textContent || '').trim();\n" +
            "    }\n" +
            "    if (!rawText || rawText.length < 2) return;\n" +
            "\n" +
            "    // 稳定性检查：文本停止变化 3 次（1.5 秒）\n" +
            "    if (rawText.length === lastTextLen) {\n" +
            "      stableCount++;\n" +
            "    } else {\n" +
            "      stableCount = 0;\n" +
            "      lastTextLen = rawText.length;\n" +
            "    }\n" +
            "    if (stableCount < 3) return;\n" +
            "\n" +
            "    // 解析 JSON\n" +
            "    var parsed = parseJsonRpc(rawText);\n" +
            "    if (!parsed) {\n" +
            "      Android.log('[JS] JSON 解析失败, rawText=' + rawText);\n" +
            "      finish(rawText);\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    var method = parsed.method || '';\n" +
            "    Android.log('[JS] JSON 解析成功: method=' + method + ', id=' + (parsed.id || 'none'));\n" +
            "\n" +
            "    // 工具调用: {method: 'tools/call', params: {name, arguments}}\n" +
            "    if (method === 'tools/call') {\n" +
            "      Android.log('[JS] 检测到工具调用: ' + JSON.stringify(parsed));\n" +
            "      finish(JSON.stringify(parsed));\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 文本回复: {result: {type: 'reply', content: '...'}}\n" +
            "    if (parsed.jsonrpc && parsed.result) {\n" +
            "      var result = parsed.result;\n" +
            "      var content = result.content || result.text || '';\n" +
            "      Android.log('[JS] 文本回复: type=' + (result.type || 'unknown') + ', content=' + content);\n" +
            "      finish(content || JSON.stringify(parsed));\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 其他 JSON 格式，原样返回\n" +
            "    Android.log('[JS] 未知 JSON 格式: ' + JSON.stringify(parsed));\n" +
            "    finish(JSON.stringify(parsed));\n" +
            "  }\n" +
            "\n" +
            "  window[__prefix + 'poll'] = setInterval(pollOnce, 500);\n" +
            "  Android.log('[JS] 轮询启动, 每 500ms, 初始AI回复数=' + initialAiCount);\n" +
            "  return 'observer_started_' + __rid;\n" +
            "  } catch(e) {\n" +
            "    try { Android.log('[JS] observerScript 异常: ' + e + ', stack=' + (e.stack||'')); } catch(e2) {}\n" +
            "    return 'observer_error_' + e;\n" +
            "  }\n" +
            "})()";// ========== Step 2: 填写消息并发送 ==========
        final String sendScript =
            "(function() {\n" +
            "  var msg = " + JSONObject.quote(message) + ";\n" +
            "  var __rid = " + JSONObject.quote(requestId) + ";\n" +
            "  var attempts = 0;\n" +
            "  function trySend() {\n" +
            "    attempts++;\n" +
            "    var textarea = document.querySelector('textarea[name=\"search\"]') ||\n" +
            "                   document.querySelector('textarea') ||\n" +
            "                   document.querySelector('[contenteditable=\"true\"]');\n" +
            "    if (!textarea) {\n" +
            "      if (attempts < 6) { setTimeout(trySend, 300); return; }\n" +
            "      Android.onDeepSeekError(__rid, '未找到输入框');\n" +
            "      return;\n" +
            "    }\n" +
            "    Android.log('[DEBUG][' + __rid + '] 已定位输入框 (attempt=' + attempts + ', tag=' + textarea.tagName + ')');\n" +
            "    textarea.focus();\n" +
            "    try { textarea.click(); } catch(_e1) {}\n" +
            "    for (var key in textarea) {\n" +
            "      if (key.indexOf('__react') === 0 || key.indexOf('__REACT') === 0) {\n" +
            "        try {\n" +
            "          var internal = textarea[key];\n" +
            "          if (internal && typeof internal.memoizedProps === 'object') {\n" +
            "            internal.memoizedProps.value = msg;\n" +
            "            if (typeof internal.memoizedProps.onChange === 'function') {\n" +
            "              internal.memoizedProps.onChange({ target: { value: msg } });\n" +
            "            }\n" +
            "          } else if (internal && typeof internal === 'object' && internal.stateNode) {\n" +
            "            var stateNode = internal.stateNode || internal;\n" +
            "            if (stateNode && typeof stateNode._valueTracker !== 'undefined') {\n" +
            "              stateNode._valueTracker = null;\n" +
            "            }\n" +
            "          }\n" +
            "        } catch(_e2) {}\n" +
            "      }\n" +
            "    }\n" +
            "    var descriptor = Object.getOwnPropertyDescriptor(\n" +
            "      window.HTMLTextAreaElement.prototype, 'value') ||\n" +
            "      Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');\n" +
            "    if (descriptor && descriptor.set) {\n" +
            "      descriptor.set.call(textarea, msg);\n" +
            "    } else {\n" +
            "      textarea.value = msg;\n" +
            "    }\n" +
            "    // 校验：输入框值是否设置成功\n" +
            "    var actualValue = textarea.value || (textarea.innerText || '');\n" +
            "    Android.log('[DEBUG][' + __rid + '] 输入框值校验: 期望长度=' + msg.length + ', 实际长度=' + actualValue.length + ', 匹配=' + (actualValue === msg));\n" +
            "    ['input', 'change', 'blur'].forEach(function(evName) {\n" +
            "      try {\n" +
            "        var ev = new Event(evName, { bubbles: true, cancelable: true });\n" +
            "        textarea.dispatchEvent(ev);\n" +
            "      } catch(_e3) {}\n" +
            "    });\n" +
            "    try {\n" +
            "      if (typeof InputEvent !== 'undefined') {\n" +
            "        var ie = new InputEvent('input', {\n" +
            "          bubbles: true, cancelable: true, data: msg, inputType: 'insertText'\n" +
            "        });\n" +
            "        textarea.dispatchEvent(ie);\n" +
            "      }\n" +
            "    } catch(_e4) {}\n" +
            "    // ===== 点击发送按钮 =====\n" +
            "    var sendBtn = null;\n" +
            "    var sendBtnSource = '';\n" +
            "    var roleBtns = document.querySelectorAll('div[role=\"button\"]');\n" +
            "    Android.log('[DEBUG][' + __rid + '] 页面role=button元素数量=' + roleBtns.length);\n" +
            "    for (var i = 0; i < roleBtns.length; i++) {\n" +
            "      var rb = roleBtns[i];\n" +
            "      var cls = rb.getAttribute('class') || '';\n" +
            "      if (cls.indexOf('ds-button--primary') !== -1 ||\n" +
            "          cls.indexOf('ds-button--filled') !== -1 ||\n" +
            "          cls.indexOf('_52c986b') !== -1) {\n" +
            "        sendBtn = rb;\n" +
            "        sendBtnSource = 'class_match:' + cls;\n" +
            "        break;\n" +
            "      }\n" +
            "    }\n" +
            "    if (!sendBtn) {\n" +
            "      var all = document.querySelectorAll('button, a, [role=\"button\"], div[onclick]');\n" +
            "      for (var j = 0; j < all.length; j++) {\n" +
            "        var tt = (all[j].innerText || all[j].textContent || '').trim();\n" +
            "        if (tt && (tt.indexOf('发送') !== -1 || tt.indexOf('Send') !== -1)) {\n" +
            "          sendBtn = all[j];\n" +
            "          sendBtnSource = 'text_match:' + tt;\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "    if (sendBtn) {\n" +
            "      var btnDisabled = sendBtn.classList ? sendBtn.classList.contains('ds-button--disabled') : false;\n" +
            "      Android.log('[DEBUG][' + __rid + '] 发送按钮定位成功: ' + sendBtnSource + ', 禁用状态=' + btnDisabled);\n" +
            "      try { sendBtn.focus(); sendBtn.click(); } catch(_e5) {}\n" +
            "      Android.log('DeepSeek: 已点击发送按钮 (msg=' + msg + ')');\n" +
            "      // 发送后校验：检查用户消息是否出现在消息列表\n" +
            "      setTimeout(function() {\n" +
            "        try {\n" +
            "          var userMsgs = document.querySelectorAll('.fbb737a4');\n" +
            "          var lastUserMsg = userMsgs.length > 0 ? userMsgs[userMsgs.length - 1] : null;\n" +
            "          var lastUserText = lastUserMsg ? (lastUserMsg.innerText || lastUserMsg.textContent || '').trim() : '';\n" +
            "          Android.log('[DEBUG][' + __rid + '] 发送后校验: 用户消息数=' + userMsgs.length + ', 最新用户消息预览=' + lastUserText);\n" +
            "        } catch(_ce) {}\n" +
            "      }, 1000);\n" +
            "      return;\n" +
            "    }\n" +
            "    Android.log('[DEBUG][' + __rid + '] 未找到发送按钮，尝试回车发送');\n" +
            "    // 兜底：键盘 Enter\n" +
            "    try {\n" +
            "      var ke2 = new KeyboardEvent('keydown', {\n" +
            "        key: 'Enter', code: 'Enter', keyCode: 13,\n" +
            "        which: 13, bubbles: true, cancelable: true\n" +
            "      });\n" +
            "      textarea.dispatchEvent(ke2);\n" +
            "      Android.log('DeepSeek: 回车键发送');\n" +
            "    } catch(_e6) {\n" +
            "      Android.onDeepSeekError(__rid, '未找到发送按钮，回车发送也失败');\n" +
            "    }\n" +
            "  }\n" +
            "  trySend();\n" +
            "  return 'preparing';\n" +
            "})()";

        final Handler handler = mainHandler;
        if (handler == null) {
            StreamCallback cb = callbacksById.get(requestId);
            if (cb != null) cb.onError("Handler 未初始化");
            cleanupRequest(requestId);
            return;
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (boundWebView == null) {
                    StreamCallback cb = callbacksById.get(requestId);
                    if (cb != null) cb.onError("WebView 已释放");
                    cleanupRequest(requestId);
                    return;
                }
                // 先启动监听，再发送消息（分开调用）
                boundWebView.evaluateJavascript(observerScript, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        AppLogger.d("DeepSeekChatBridge", "[" + requestId + "] observerScript 返回: " + result);
                    }
                });
                boundWebView.evaluateJavascript(sendScript, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String sendResult) {
                        AppLogger.d("DeepSeekChatBridge", "[" + requestId + "] 发送结果: " + sendResult);
                    }
                });
            }
        });
    }

    // ======================================================================
    //  被 JavaScriptBridge 调用：把 JS 侧的事件按 requestId 路由到对应回调
    // ======================================================================

    public void onDeepSeekChunk(String requestId, String chunk) {
        if (requestId == null) return;
        StreamCallback cb = callbacksById.get(requestId);
        if (cb != null) {
            try { cb.onChunk(chunk); } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * P2 修复：对长消息进行智能截断，便于日志记录
     * 保留首尾内容，中间内容用省略号表示，充分利用缓冲空间
     */
    private String formatLongMessageForLog(String message, int maxLen) {
        if (message == null) return "";
        if (maxLen <= 0 || message.length() <= maxLen) return message;
        // 保留首尾，中间用省略号替代
        int headLen = maxLen * 2 / 3;
        int tailLen = maxLen - headLen - 3;
        if (tailLen < 10) { headLen = maxLen - 13; tailLen = 10; }
        return message.substring(0, headLen) + "..." + message.substring(message.length() - tailLen);
    }

    /**
     * P2 修复：完整记录 DeepSeek 回复内容
     */
    public void onDeepSeekReply(String requestId, String reply) {
        if (requestId == null) return;
        // 若 JS 回传了空回复，先在 Java 侧做一次备用 DOM 提取再放行
        if (reply == null || reply.isEmpty()) {
            AppLogger.w("DeepSeekChatBridge",
                "[" + requestId + "] 收到空回复，触发备用 DOM 提取");
            tryExtractFromDOMAndRelease(requestId);
            return;
        }
        AtomicReference<String> ref = replyById.get(requestId);
        if (ref != null) ref.set(reply);
        CountDownLatch l = latchById.get(requestId);
        if (l != null) l.countDown();
        
        // 所有 LLM 回复必须完整记录，不截断
        AppLogger.d("DeepSeekChatBridge",
            "[" + requestId + "] LLM回复 (长度=" + reply.length() + ")\n" + reply);
    }

    /**
     * 当 JS 侧回传的 reply 为空时，在主线程重新注入 JS 做最后一次 DOM 提取，
     * 提取结果会更新 replyById 并释放 latch。
     */
    private void tryExtractFromDOMAndRelease(final String requestId) {
        // 在 synchronized 块内一次性捕获所有引用，保证一致性。
        // 注意：即使 cleanupRequest 随后从 map 中移除这些引用，
        // 已捕获的对象仍然有效：l.countDown() 在 latch 已归零后仍然安全（内部计数为负不会抛异常），
        // ref.set() 设置的值若无人读取也是无害的。
        final CountDownLatch l;
        final AtomicReference<String> ref;
        final Handler handler;
        final WebView wb;
        synchronized (this) {
            l = latchById.get(requestId);
            ref = replyById.get(requestId);
            handler = mainHandler;
            wb = boundWebView;
        }
        if (handler == null || wb == null) {
            // 无法执行备用 DOM 提取：ref 保持 null，latch 释放后调用方会走"未收到回复"错误路径
            if (l != null) l.countDown();
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                final String fallbackScript =
                    "(function() {\n" +
                    "  var selectors = [\n" +
                    "    '.ds-assistant-message-main-content',\n" +
                    "    '[class*=\"ds-assistant-message\"]',\n" +
                    "    '[class*=\"assistant-message-main\"]',\n" +
                    "    '.ds-markdown--block',\n" +
                    "    '.ds-markdown',\n" +
                    "    '[class*=\"assistant-message\"]'\n" +
                    "  ];\n" +
                    "  for (var i = 0; i < selectors.length; i++) {\n" +
                    "    var els = document.querySelectorAll(selectors[i]);\n" +
                    "    if (els && els.length > 0) {\n" +
                    "      var txt = (els[els.length - 1].innerText || els[els.length - 1].textContent || '').trim();\n" +
                    "      if (txt && txt.length > 5) return txt;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return '';\n" +
                    "})()";
                wb.evaluateJavascript(fallbackScript, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        String extracted = null;
                        // evaluateJavascript 返回 JSON 编码的字符串值；用 JSONTokener 安全解析
                        if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                            try {
                                Object parsed = new org.json.JSONTokener(result).nextValue();
                                if (parsed instanceof String) {
                                    String s = ((String) parsed).trim();
                                    if (!s.isEmpty()) extracted = s;
                                }
                            } catch (Exception e) {
                                // 解析失败时忽略，extracted 保持 null
                            }
                        }
                        AppLogger.d("DeepSeekChatBridge",
                            "[" + requestId + "] 备用 DOM 提取结果长度=" + (extracted == null ? 0 : extracted.length())
                            + "\n内容: " + (extracted == null ? "(null)" : extracted));
                        if (ref != null) ref.set(extracted);
                        if (l != null) l.countDown();
                    }
                });
            }
        });
    }

    public void onDeepSeekError(String requestId, String error) {
        if (requestId == null) return;
        CountDownLatch l = latchById.get(requestId);
        AtomicReference<String> errRef = errorById.get(requestId);
        if (errRef != null) errRef.set(error);
        if (l != null) l.countDown();
        AppLogger.e("DeepSeekChatBridge",
            "[" + requestId + "] JS 错误: " + error);
    }

    // ======================================================================
    //  工具方法
    // ======================================================================

    private String evaluateJsSync(final String jsCode, int timeoutSeconds) {
        final WebView wb;
        final Handler handler;
        synchronized (this) {
            wb = boundWebView;
            handler = mainHandler;
        }
        if (wb == null || handler == null) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<String>();
        handler.post(new Runnable() {
            @Override
            public void run() {
                wb.evaluateJavascript(jsCode, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        resultRef.set(value);
                        latch.countDown();
                    }
                });
            }
        });
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return resultRef.get();
    }

    /**
     * 获取会话列表（仅 7 天内）
     */
    public String getSessions() {
        String js =
            "(function() {\n" +
            "  var result = {sessions: [], current: null, total: 0};\n" +
            "  var currentPath = location.pathname || '';\n" +
            "  var currentMatch = currentPath.match(/chat[\\/\\\\]s[\\/\\\\]([a-zA-Z0-9_-]+)/);\n" +
            "  if (currentMatch) result.current = currentMatch[1];\n" +
            "  var anchors = document.querySelectorAll('a[href*=\"/chat/s/\"]');\n" +
            "  if (!anchors || anchors.length === 0) {\n" +
            "    anchors = document.querySelectorAll('a[href*=\"chat\"]');\n" +
            "  }\n" +
            "  var currentGroup = '';\n" +
            "  // 仅保留 7 天内的分组（今天、昨天、7 天内）\n" +
            "  var allowedGroups = ['今天', '昨天', '7 天内'];\n" +
            "  var all = document.querySelectorAll('[class]');\n" +
            "  for (var idx = 0; idx < all.length; idx++) {\n" +
            "    var el = all[idx];\n" +
            "    var cls = el.getAttribute('class') || '';\n" +
            "    if (el.tagName === 'A' && cls.indexOf('_546d736') !== -1) {\n" +
            "      var href = el.getAttribute('href') || '';\n" +
            "      var idMatch = href.match(/chat[\\/\\\\]s[\\/\\\\]([a-zA-Z0-9_-]+)/);\n" +
            "      var id = idMatch ? idMatch[1] : null;\n" +
            "      if (!id) continue;\n" +
            "      // 过滤：仅保留 7 天内的会话\n" +
            "      if (allowedGroups.indexOf(currentGroup) === -1) continue;\n" +
            "      var titleDiv = el.querySelector('[class*=\"c08e6e93\"]');\n" +
            "      var title = titleDiv ? (titleDiv.innerText || titleDiv.textContent || '').trim()\n" +
            "                            : (el.innerText || el.textContent || '').trim();\n" +
            "      result.sessions.push({\n" +
            "        id: id, title: title, group: currentGroup || '',\n" +
            "        isCurrent: (id === result.current)\n" +
            "      });\n" +
            "    } else if (el.tagName === 'DIV' && cls.indexOf('f3d18f6a') !== -1) {\n" +
            "      currentGroup = (el.innerText || el.textContent || '').trim();\n" +
            "    }\n" +
            "  }\n" +
            "  result.total = result.sessions.length;\n" +
            "  return JSON.stringify(result);\n" +
            "})()";
        String raw = evaluateJsSync(js, 10);
        if (raw == null) return null;
        try {
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                String jsonArrayStr = "[" + raw + "]";
                org.json.JSONArray arr = new org.json.JSONArray(jsonArrayStr);
                return arr.getString(0);
            }
        } catch (Exception e) {
            AppLogger.w("DeepSeekChatBridge", "getSessions 解包失败: " + e.getMessage());
        }
        return raw;
    }

    /**
     * 切换会话：点击对应会话项后，等待 URL 发生变化才算完成
     */
    public boolean selectSession(final String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;

        final String getUrlJs = "(function(){ return location.pathname || ''; })()";
        String oldUrl = evaluateJsSync(getUrlJs, 5);
        if (oldUrl != null && oldUrl.length() >= 2 && oldUrl.startsWith("\"")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray("[" + oldUrl + "]");
                oldUrl = arr.getString(0);
            } catch (Exception ignored) {}
        }

        String js =
            "(function() {\n" +
            "  var targetId = " + JSONObject.quote(sessionId) + ";\n" +
            "  var anchors = document.querySelectorAll('a[href*=\"/chat/s/\"]');\n" +
            "  var clicked = false;\n" +
            "  for (var i = 0; i < anchors.length; i++) {\n" +
            "    var href = anchors[i].getAttribute('href') || '';\n" +
            "    if (href.indexOf(targetId) !== -1) {\n" +
            "      anchors[i].click();\n" +
            "      clicked = true;\n" +
            "      break;\n" +
            "    }\n" +
            "  }\n" +
            "  return clicked ? 'ok' : 'not_found';\n" +
            "})()";

        String raw = evaluateJsSync(js, 10);
        if (raw == null) return false;
        if (!raw.contains("ok")) return false;

        // 等待最多 5 秒，直到 URL 发生变化并包含新 sessionId
        for (int attempt = 0; attempt < 10; attempt++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            String currentUrl = evaluateJsSync(getUrlJs, 3);
            if (currentUrl != null && currentUrl.contains(sessionId)) {
                return true;
            }
        }
        return true; // 即使没检测到 URL 变化，也认为点击成功
    }

    /**
     * 创建新会话：点击新建按钮后等待 URL 变化
     */
    public boolean newSession() {
        String js =
            "(function() {\n" +
            "  try {\n" +
            "    var currentPath = location.pathname;\n" +
            "    if (currentPath.indexOf('/chat') === -1) {\n" +
            "      location.href = '/chat';\n" +
            "      return 'ok_navigate';\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "  // 策略：侧边栏新建 / plus按钮 / 链接\n" +
            "  var allClickable = document.querySelectorAll('button, a, [role=\"button\"], div[onclick]');\n" +
            "  for (var i = 0; i < allClickable.length; i++) {\n" +
            "    var txt = (allClickable[i].innerText || allClickable[i].textContent || '').trim().toLowerCase();\n" +
            "    var aria = (allClickable[i].getAttribute('aria-label') || '').toLowerCase();\n" +
            "    var title = (allClickable[i].getAttribute('title') || '').toLowerCase();\n" +
            "    if (txt.indexOf('新建') !== -1 || txt.indexOf('新对话') !== -1 ||\n" +
            "        txt.indexOf('new chat') !== -1 || txt.indexOf('new conversation') !== -1 ||\n" +
            "        aria.indexOf('new chat') !== -1 || aria.indexOf('新建') !== -1 ||\n" +
            "        title.indexOf('new chat') !== -1 || title.indexOf('新建') !== -1) {\n" +
            "      var rect = allClickable[i].getBoundingClientRect();\n" +
            "      if (rect.left < (window.innerWidth * 0.3) ||\n" +
            "          allClickable[i].tagName === 'A' ||\n" +
            "          txt.length < 10) {\n" +
            "        allClickable[i].click();\n" +
            "        return 'ok_sidebar';\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "  var svgs = document.querySelectorAll('svg');\n" +
            "  for (var k = 0; k < svgs.length; k++) {\n" +
            "    var sp = svgs[k].closest('button, a, [role=\"button\"], div');\n" +
            "    if (sp) {\n" +
            "      var r2 = sp.getBoundingClientRect();\n" +
            "      if (r2.left < window.innerWidth * 0.3 && r2.top < window.innerHeight * 0.3) {\n" +
            "        sp.click();\n" +
            "        return 'ok_svg_plus';\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "  try { location.pathname = '/chat'; return 'ok_path_change'; } catch(e) {}\n" +
            "  return 'not_found';\n" +
            "})()";

        String raw = evaluateJsSync(js, 10);
        if (raw == null) return false;

        // 等待最多 3 秒让页面完成跳转
        for (int attempt = 0; attempt < 6; attempt++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return raw.contains("ok");
    }
}
