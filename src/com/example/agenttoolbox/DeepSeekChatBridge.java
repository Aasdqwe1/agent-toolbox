package com.example.agenttoolbox;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek HTTP 聊天桥接层
 *
 * 负责跨 Activity 通信：
 * - McpServer（HTTP 线程）通过此类向 DeepSeekActivity 的 WebView 发送消息
 * - 等待 WebView 通过 JS 注入发消息、MutationObserver 监听到 AI 回复
 * - 回复内容通过 CountDownLatch 同步返回给 HTTP 线程
 *
 * 使用方式：
 *   DeepSeekActivity.onCreate()   → DeepSeekChatBridge.register(this, webView)
 *   DeepSeekActivity.onDestroy() → DeepSeekChatBridge.unregister()
 *   HTTP 请求线程                  → DeepSeekChatBridge.sendMessage(message) → 等待回复
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

    // 注册 / 注销
    public synchronized void register(WebView webView) {
        this.boundWebView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        android.util.Log.d("DeepSeekChatBridge", "已注册 WebView: " + (webView != null ? "有效" : "null"));
    }

    public synchronized void unregister() {
        this.boundWebView = null;
        this.mainHandler = null;
        android.util.Log.d("DeepSeekChatBridge", "已注销 WebView");
    }

    public synchronized boolean isRegistered() {
        return boundWebView != null;
    }

    /**
     * 发送聊天消息并等待回复（同步阻塞，最长 60 秒）
     *
     * @param message 用户输入的消息
     * @return DeepSeek 回复文本，超时返回 null
     */
    public String sendMessage(final String message) {
        final WebView wb;
        final Handler handler;
        synchronized (this) {
            wb = boundWebView;
            handler = mainHandler;
        }

        if (wb == null || handler == null) {
            android.util.Log.w("DeepSeekChatBridge", "WebView 未注册，无法发送消息");
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> replyRef = new AtomicReference<String>();
        final AtomicReference<String> errorRef = new AtomicReference<String>();
        final long startTime = System.currentTimeMillis();

        // 在主线程注入 JS：发消息 + 监听回复
        handler.post(new Runnable() {
            @Override
            public void run() {
                injectChatScript(wb, message, latch, replyRef, errorRef);
            }
        });

        // HTTP 线程阻塞等待
        try {
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                android.util.Log.w("DeepSeekChatBridge", "等待回复超时（60s）");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            android.util.Log.e("DeepSeekChatBridge", "等待被中断", e);
            return null;
        }

        String reply = replyRef.get();
        if (reply != null) {
            android.util.Log.d("DeepSeekChatBridge", "收到回复（" +
                (System.currentTimeMillis() - startTime) + "ms）：" +
                reply.substring(0, Math.min(100, reply.length())) + "...");
        }
        return reply;
    }

    /**
     * 向 WebView 注入 JS：填写输入框 → 点击发送 → 监听回复
     */
    private void injectChatScript(final WebView webView, final String message,
                                  final CountDownLatch latch,
                                  final AtomicReference<String> replyRef,
                                  final AtomicReference<String> errorRef) {

        if (webView == null) {
            errorRef.set("WebView 为 null");
            latch.countDown();
            return;
        }

        // Step 1: 注册 MutationObserver，等待 AI 回复出现
        String observerScript = "(function() {\n" +
            "  if (window.__deepseekReplyObserved) return;\n" +
            "  window.__deepseekReplyObserved = true;\n" +
            "  window.__deepseekLastReply = '';\n" +
            "  window.__deepseekPendingLatch = " + latch.hashCode() + ";\n" +
            "\n" +
            "  // 查找最新 assistant 消息的函数\n" +
            "  function getLatestAssistantReply() {\n" +
            "    // 尝试多种选择器找 AI 回复\n" +
            "    var candidates = document.querySelectorAll(\n" +
            "      '[class*=\"markdown\"]', '[class*=\"assistant\"]', '[class*=\"prose\"]',\n" +
            "      '.message-body', '.chat-message', '[data-testid*=\"assistant\"]',\n" +
            "      '[role=\"article\"]', 'article', '.whitespace-pre-wrap'\n" +
            "    );\n" +
            "    var texts = [];\n" +
            "    for (var i = 0; i < candidates.length; i++) {\n" +
            "      var el = candidates[i];\n" +
            "      // 过滤掉用户消息（包含用户头像、用户相关 class）\n" +
            "      var parent = el.closest ? el.closest('[class*=\"user\"]') : null;\n" +
            "      if (parent) continue;\n" +
            "      var txt = (el.innerText || el.textContent || '').trim();\n" +
            "      if (txt && txt.length > 10) texts.push(txt);\n" +
            "    }\n" +
            "    if (texts.length === 0) return null;\n" +
            "    return texts[texts.length - 1]; // 最后一条（最新）\n" +
            "  }\n" +
            "\n" +
            "  // 检查是否已经在生成回复（检查停止按钮 / 加载状态）\n" +
            "  function isGenerating() {\n" +
            "    var stopBtn = document.querySelector('[class*=\"stop\"]');\n" +
            "    var loading = document.querySelector('[class*=\"loading\"]');\n" +
            "    var thinking = document.querySelector('[class*=\"thinking\"]');\n" +
            "    return !!(stopBtn || loading || thinking);\n" +
            "  }\n" +
            "\n" +
            "  // 主动轮询（兜底，确保 observer 漏掉时也能捕获）\n" +
            "  var pollCount = 0;\n" +
            "  var lastReply = '';\n" +
            "  var pollInterval = setInterval(function() {\n" +
            "    pollCount++;\n" +
            "    var currentReply = getLatestAssistantReply();\n" +
            "    var generating = isGenerating();\n" +
            "\n" +
            "    // 有新回复且生成停止 → 发送\n" +
            "    if (currentReply && currentReply !== lastReply && !generating && currentReply.length > 5) {\n" +
            "      lastReply = currentReply;\n" +
            "      clearInterval(pollInterval);\n" +
            "      window.__deepseekLastReply = currentReply;\n" +
            "      Android.onDeepSeekReply(currentReply);\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 超时 50s 直接返回当前内容\n" +
            "    if (pollCount > 100) {\n" +
            "      clearInterval(pollInterval);\n" +
            "      var finalReply = getLatestAssistantReply() || window.__deepseekLastReply || '';\n" +
            "      window.__deepseekLastReply = finalReply;\n" +
            "      if (finalReply) Android.onDeepSeekReply(finalReply);\n" +
            "    }\n" +
            "  }, 500);\n" +
            "\n" +
            "  // MutationObserver 监听 DOM 变化\n" +
            "  var observer = new MutationObserver(function(mutations) {\n" +
            "    var reply = getLatestAssistantReply();\n" +
            "    var generating = isGenerating();\n" +
            "    if (reply && reply !== lastReply && !generating) {\n" +
            "      lastReply = reply;\n" +
            "      clearInterval(pollInterval);\n" +
            "      window.__deepseekLastReply = reply;\n" +
            "      Android.onDeepSeekReply(reply);\n" +
            "      observer.disconnect();\n" +
            "    }\n" +
            "  });\n" +
            "\n" +
            "  var target = document.body || document.documentElement;\n" +
            "  if (target) {\n" +
            "    observer.observe(target, { childList: true, subtree: true, characterData: true });\n" +
            "  }\n" +
            "\n" +
            "  return 'observer_started';\n" +
            "})()";

        // Step 2: 填写消息并发送
        String sendScript =
            "(function() {\n" +
            "  var msg = " + JSONObject.quote(message) + ";\n" +
            "\n" +
            "  // 查找输入框\n" +
            "  var textarea = document.querySelector('textarea');\n" +
            "  var editable = document.querySelector('[contenteditable=\"true\"]');\n" +
            "  var input = textarea || editable;\n" +
            "\n" +
            "  if (!input) {\n" +
            "    Android.onDeepSeekError('未找到输入框（textarea 或 contenteditable）');\n" +
            "    return 'no_input';\n" +
            "  }\n" +
            "\n" +
            "  // 聚焦并填入文字\n" +
            "  input.focus();\n" +
            "  input.click();\n" +
            "\n" +
            "  // 填入消息（模拟用户输入，触发 Vue/React 响应）\n" +
            "  var nativeInputValueSetter =\n" +
            "    Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value') ||\n" +
            "    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');\n" +
            "  if (nativeInputValueSetter) {\n" +
            "    nativeInputValueSetter.set.call(input, msg);\n" +
            "  } else {\n" +
            "    input.value = msg;\n" +
            "  }\n" +
            "\n" +
            "  // 触发 input 事件\n" +
            "  var inputEvent = new Event('input', { bubbles: true });\n" +
            "  input.dispatchEvent(inputEvent);\n" +
            "\n" +
            "  // 触发 change 事件\n" +
            "  var changeEvent = new Event('change', { bubbles: true });\n" +
            "  input.dispatchEvent(changeEvent);\n" +
            "\n" +
            "  // 延迟一点再点击发送按钮\n" +
            "  setTimeout(function() {\n" +
            "    // 查找发送按钮\n" +
            "    var sendBtn = null;\n" +
            "\n" +
            "    // 策略1：找 submit / 发送 相关按钮\n" +
            "    var buttons = document.querySelectorAll('button');\n" +
            "    for (var i = 0; i < buttons.length; i++) {\n" +
            "      var txt = (buttons[i].innerText || '').trim();\n" +
            "      if (txt.indexOf('发送') >= 0 || txt.indexOf('Send') >= 0 ||\n" +
            "          txt.indexOf('提交') >= 0 || txt.indexOf('Submit') >= 0 ||\n" +
            "          buttons[i].getAttribute('class') &&\n" +
            "          buttons[i].getAttribute('class').indexOf('send') >= 0) {\n" +
            "        sendBtn = buttons[i];\n" +
            "        break;\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 策略2：找输入框同容器下的最后一个可用按钮\n" +
            "    if (!sendBtn) {\n" +
            "      var container = input.closest('div');\n" +
            "      if (container) {\n" +
            "        var btns = container.querySelectorAll('button');\n" +
            "        for (var j = btns.length - 1; j >= 0; j--) {\n" +
            "          if (!btns[j].disabled) { sendBtn = btns[j]; break; }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 策略3：键盘回车发送\n" +
            "    if (!sendBtn) {\n" +
            "      var enterEvent = new KeyboardEvent('keydown', {\n" +
            "        key: 'Enter', code: 'Enter', keyCode: 13,\n" +
            "        which: 13, bubbles: true\n" +
            "      });\n" +
            "      input.dispatchEvent(enterEvent);\n" +
            "      Android.log('DeepSeek: 使用回车键发送消息');\n" +
            "      return 'enter_sent';\n" +
            "    }\n" +
            "\n" +
            "    if (sendBtn) {\n" +
            "      sendBtn.click();\n" +
            "      Android.log('DeepSeek: 已点击发送按钮');\n" +
            "      return 'clicked';\n" +
            "    } else {\n" +
            "      Android.onDeepSeekError('未找到发送按钮');\n" +
            "      return 'no_send_btn';\n" +
            "    }\n" +
            "  }, 300);\n" +
            "\n" +
            "  return 'preparing';\n" +
            "})()";

        // 先注册回调
        deepSeekReplyCallback = replyRef;
        deepSeekErrorCallback = errorRef;
        deepSeekLatch = latch;

        // Step 1: 启动监听
        webView.evaluateJavascript(observerScript, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                android.util.Log.d("DeepSeekChatBridge", "Observer 启动: " + value);
                // Step 2: 发送消息
                webView.evaluateJavascript(sendScript, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String sendResult) {
                        android.util.Log.d("DeepSeekChatBridge", "发送结果: " + sendResult);
                    }
                });
            }
        });
    }

    // 回调（由 JavaScriptBridge.onDeepSeekReply 调用）
    private volatile CountDownLatch deepSeekLatch;
    private volatile AtomicReference<String> deepSeekReplyCallback;
    private volatile AtomicReference<String> deepSeekErrorCallback;

    /**
     * 通用：在主线程同步执行 JS 代码并等待返回值（最长 10 秒）
     * 用于不需要 `Android.*` 回调的纯 DOM 查询场景。
     */
    private String evaluateJsSync(final String jsCode, int timeoutSeconds) {
        final WebView wb;
        final Handler handler;
        synchronized (this) {
            wb = boundWebView;
            handler = mainHandler;
        }
        if (wb == null || handler == null) {
            android.util.Log.w("DeepSeekChatBridge", "evaluateJsSync: WebView 未注册");
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<String>();

        final WebView finalWb = wb;
        handler.post(new Runnable() {
            @Override
            public void run() {
                finalWb.evaluateJavascript(jsCode, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        resultRef.set(value);
                        latch.countDown();
                    }
                });
            }
        });

        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                android.util.Log.w("DeepSeekChatBridge", "evaluateJsSync: 超时");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return resultRef.get();
    }

    /**
     * 获取会话列表
     *
     * 真实 DOM 示例：
     *   <a class="_546d736" href="/a/chat/s/f42a7fb5-2d45-4ad4-8011-0c94f0a8e2ac">
     *     <div class="c08e6e93">已登录DeepSeek</div>
     *   </a>
     *   <div class="f3d18f6a">今天</div>
     *
     * 返回 JSON 示例：
     *   {
     *     "success": true,
     *     "total": 2,
     *     "current": "f42a7fb5-2d45-4ad4-8011-0c94f0a8e2ac",
     *     "sessions": [
     *       {"id": "...", "title": "已登录DeepSeek", "group": "今天", "isCurrent": true},
     *       ...
     *     ]
     *   }
     */
    public String getSessions() {
        String js =
            "(function() {\n" +
            "  var result = {sessions: [], current: null, total: 0, _url: location.pathname};\n" +
            "  var currentPath = location.pathname || '';\n" +
            "\n" +
            "  // 当前会话 ID：从 URL 提取 /a/chat/s/{id}\n" +
            "  var currentMatch = currentPath.match(/chat[\\\\/\\\\\\\\]s[\\\\/\\\\\\\\]([a-zA-Z0-9_-]+)/);\n" +
            "  if (currentMatch) result.current = currentMatch[1];\n" +
            "\n" +
            "  // 提取所有会话项：a._546d736 或 a[href*='/a/chat/s/']\n" +
            "  var anchors = document.querySelectorAll('a[href*=\"/chat/s/\"]');\n" +
            "  if (anchors.length === 0) {\n" +
            "    anchors = document.querySelectorAll('a[href*=\"chat\"]');\n" +
            "  }\n" +
            "\n" +
            "  // 分组标签（遇到新的分组标签后，后续会话归到该分组）\n" +
            "  var currentGroup = '';\n" +
            "\n" +
            "  // 为了支持分组遍历，改用树顺序遍历所有候选元素\n" +
            "  // 策略：遍历页面中所有带 class 的元素，遇到 f3d18f6a 即切换分组，遇到 _546d736 即添加会话\n" +
            "  var all = document.querySelectorAll('[class]');\n" +
            "  for (var idx = 0; idx < all.length; idx++) {\n" +
            "    var el = all[idx];\n" +
            "    var cls = el.getAttribute('class') || '';\n" +
            "\n" +
            "    if (el.tagName === 'A' && cls.indexOf('_546d736') !== -1) {\n" +
            "      // 会话项\n" +
            "      var href = el.getAttribute('href') || '';\n" +
            "      var idMatch = href.match(/chat[\\\\/\\\\\\\\]s[\\\\/\\\\\\\\]([a-zA-Z0-9_-]+)/);\n" +
            "      var id = idMatch ? idMatch[1] : null;\n" +
            "      if (!id) continue;\n" +
            "\n" +
            "      // 标题：内部 c08e6e93 的文字，如没有则取整个链接的 innerText\n" +
            "      var titleDiv = el.querySelector('[class*=\"c08e6e93\"]');\n" +
            "      var title = titleDiv ? (titleDiv.innerText || titleDiv.textContent || '').trim()\n" +
            "                           : (el.innerText || el.textContent || '').trim();\n" +
            "\n" +
            "      result.sessions.push({\n" +
            "        id: id,\n" +
            "        title: title,\n" +
            "        group: currentGroup || '',\n" +
            "        isCurrent: (id === result.current)\n" +
            "      });\n" +
            "    } else if (el.tagName === 'DIV' && cls.indexOf('f3d18f6a') !== -1) {\n" +
            "      // 分组标签\n" +
            "      currentGroup = (el.innerText || el.textContent || '').trim();\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  result.total = result.sessions.length;\n" +
            "  return JSON.stringify(result);\n" +
            "})()";

        String raw = evaluateJsSync(js, 10);
        if (raw == null) return null;
        // evaluateJavascript 返回 JSON 字符串（带外层双引号），需要解包
        try {
            // Android.evaluateJavascript 会把 JS 返回值序列化成 JSON 值；
            // 当 JS 返回 JSON.stringify(...) 时，Java 端拿到的是带外层双引号的字符串
            // 例如 "\"{\\\"sessions\\\":[...]}\""
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                // 使用 JSONObject 来解包，避免手动处理转义出错
                java.util.ArrayList<String> list = new java.util.ArrayList<String>();
                list.add(raw);
                org.json.JSONArray arr = new org.json.JSONArray(list);
                return arr.getString(0);
            }
        } catch (Exception e) {
            android.util.Log.w("DeepSeekChatBridge", "getSessions 解包失败: " + e.getMessage());
        }
        return raw;
    }

    /**
     * 切换会话：点击对应会话 ID 的链接
     *
     * @param sessionId 目标会话 ID
     * @return true 表示找到了元素并点击成功
     */
    public boolean selectSession(final String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;

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
        return raw.contains("ok");
    }

    /**
     * 被 JavaScriptBridge 回调：AI 回复已捕获
     */
    public void onDeepSeekReply(String reply) {
        CountDownLatch l = deepSeekLatch;
        AtomicReference<String> ref = deepSeekReplyCallback;
        if (l != null && ref != null) {
            ref.set(reply);
            l.countDown();
        }
        // 重置状态
        deepSeekLatch = null;
        deepSeekReplyCallback = null;
        deepSeekErrorCallback = null;
    }

    /**
     * 被 JavaScriptBridge 回调：发生错误
     */
    public void onDeepSeekError(String error) {
        android.util.Log.e("DeepSeekChatBridge", "WebView 错误: " + error);
        CountDownLatch l = deepSeekLatch;
        AtomicReference<String> ref = deepSeekErrorCallback;
        if (l != null && ref != null) {
            ref.set(error);
            l.countDown();
        }
        // 重置状态
        deepSeekLatch = null;
        deepSeekReplyCallback = null;
        deepSeekErrorCallback = null;
    }
}
