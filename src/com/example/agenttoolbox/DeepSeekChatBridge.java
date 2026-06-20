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
    private boolean webViewLoaded;  // 已加载过 DeepSeek 页面 → 跳过重新 loadUrl

    // 注册 / 注销
    public synchronized void register(WebView webView) {
        this.boundWebView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        android.util.Log.d("DeepSeekChatBridge", "已注册 WebView: " + (webView != null ? "有效" : "null"));
    }

    // Activity 返回/销毁时调用：保持 WebView 存活，仅从视图树 detach
    public synchronized void detach() {
        // 不 unregister，不 destroy — boundWebView 和 mainHandler 保持不变
        android.util.Log.d("DeepSeekChatBridge", "detach: WebView 保持存活，HTTP API 继续可用");
    }

    public synchronized void unregister() {
        this.boundWebView = null;
        this.mainHandler = null;
        this.webViewLoaded = false;
        android.util.Log.d("DeepSeekChatBridge", "已注销 WebView");
    }

    public synchronized WebView getBoundWebView() { return boundWebView; }
    public synchronized boolean isRegistered() { return boundWebView != null; }
    public synchronized boolean isWebViewLoaded() { return webViewLoaded && boundWebView != null; }
    public synchronized void markAsLoaded() { this.webViewLoaded = true; }

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
        // 重置标志，确保每次发送都有新的监听
        final String observerScript = "(function() {\n" +
            "  window.__deepseekReplyObserved = false;\n" +
            "  window.__deepseekLastReply = '';\n" +
            "  window.__deepseekPendingLatch = " + latch.hashCode() + ";\n" +
            "\n" +
            "  // 查找最新 assistant 消息的函数\n" +
            "  function getLatestAssistantReply() {\n" +
            "    // 策略1：找所有看起来像 AI 回复的元素（按 DOM 顺序取最后一个）\n" +
            "    var candidates = document.querySelectorAll(\n" +
            "      '[class*=\"prose\"]', '[class*=\"markdown\"]', '[class*=\"assistant\"]',\n" +
            "      '.whitespace-pre-wrap', '[role=\"article\"]', 'article',\n" +
            "      '.message-body', '.chat-message'\n" +
            "    );\n" +
            "\n" +
            "    var texts = [];\n" +
            "    for (var i = 0; i < candidates.length; i++) {\n" +
            "      var el = candidates[i];\n" +
            "      // 过滤掉在用户消息区域内的元素（通过父级 class 判断）\n" +
            "      var parent = el.closest ? el.closest('[class*=\"user\"]') : null;\n" +
            "      if (parent) continue;\n" +
            "      // 排除输入框本身\n" +
            "      if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') continue;\n" +
            "      var txt = (el.innerText || el.textContent || '').trim();\n" +
            "      if (txt && txt.length > 5) texts.push(txt);\n" +
            "    }\n" +
            "\n" +
            "    // 策略2：取所有 role=article 的元素，或取最深层的 prose 文本\n" +
            "    if (texts.length === 0) {\n" +
            "      var articles = document.querySelectorAll('[role=\"article\"], article');\n" +
            "      for (var k = 0; k < articles.length; k++) {\n" +
            "        var artTxt = (articles[k].innerText || articles[k].textContent || '').trim();\n" +
            "        if (artTxt && artTxt.length > 10) texts.push(artTxt);\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    if (texts.length === 0) return null;\n" +
            "    return texts[texts.length - 1]; // 最后一条（最新）\n" +
            "  }\n" +
            "\n" +
            "  // 检查是否在生成回复（DeepSeek 新版本：生成时出现停止按钮，发送按钮消失）\n" +
            "  function isGenerating() {\n" +
            "    // 策略A：生成中出现停止按钮（div[role=\"button\"], 非 primary）\n" +
            "    var stopBtn = document.querySelector('[class*=\"stop\"]');\n" +
            "    // 策略B：发送按钮是否存在（不存在=正在生成）\n" +
            "    var sendBtn = document.querySelector('div[role=\"button\"][class*=\"ds-button--primary\"]');\n" +
            "    var hasSecondaryBtn = false;\n" +
            "    var roleBtns = document.querySelectorAll('div[role=\"button\"]');\n" +
            "    for (var i = 0; i < roleBtns.length; i++) {\n" +
            "      var c = roleBtns[i].getAttribute('class') || '';\n" +
            "      if (c.indexOf('ds-button--secondary') !== -1 || c.indexOf('ds-button--ghost') !== -1) {\n" +
            "        hasSecondaryBtn = true;\n" +
            "        break;\n" +
            "      }\n" +
            "    }\n" +
            "    // 策略C：loading / thinking\n" +
            "    var loading = document.querySelector('[class*=\"loading\"]');\n" +
            "    var thinking = document.querySelector('[class*=\"thinking\"]');\n" +
            "\n" +
            "    return !!(stopBtn || (!sendBtn && hasSecondaryBtn) || loading || thinking);\n" +
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
        // 注意：DeepSeek 新版本页面
        //   - 输入框: <textarea name="search" class="_27c9245 ...">
        //   - 发送按钮: <div role="button" class="ds-button ds-button--primary ... _52c986b">
        //   - 停止按钮: <div role="button" class="ds-button ...">  (生成中出现)
        final String sendScript =
            "(function() {\n" +
            "  var msg = " + JSONObject.quote(message) + ";\n" +
            "\n" +
            "  // ============ 1. 定位输入框 ============\n" +
            "  var textarea = document.querySelector('textarea[name=\"search\"]') ||\n" +
            "                 document.querySelector('textarea') ||\n" +
            "                 document.querySelector('[contenteditable=\"true\"]');\n" +
            "  if (!textarea) {\n" +
            "    Android.onDeepSeekError('未找到输入框');\n" +
            "    return 'no_input';\n" +
            "  }\n" +
            "  Android.log('DeepSeek: 已定位输入框');\n" +
            "\n" +
            "  // ============ 2. 模拟用户输入（触发 React 受控组件） ============\n" +
            "  textarea.focus();\n" +
            "  textarea.click();\n" +
            "\n" +
            "  // 2a. 通过 React 内部属性直接设置（React 16/17/18 兼容）\n" +
            "  var reactPropSet = false;\n" +
            "  for (var key in textarea) {\n" +
            "    if (key.indexOf('__react') === 0 || key.indexOf('__REACT') === 0) {\n" +
            "      try {\n" +
            "        var internal = textarea[key];\n" +
            "        if (internal && typeof internal.memoizedProps === 'object') {\n" +
            "          internal.memoizedProps.value = msg;\n" +
            "          if (typeof internal.memoizedProps.onChange === 'function') {\n" +
            "            internal.memoizedProps.onChange({ target: { value: msg } });\n" +
            "          }\n" +
            "          reactPropSet = true;\n" +
            "        } else if (internal && typeof internal === 'object' && internal.stateNode) {\n" +
            "          // React Fiber\n" +
            "          var stateNode = internal.stateNode || internal;\n" +
            "          if (stateNode && typeof stateNode._valueTracker !== 'undefined') {\n" +
            "            stateNode._valueTracker = null;\n" +
            "          }\n" +
            "          reactPropSet = true;\n" +
            "        }\n" +
            "      } catch(e) {}\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 2b. 通过 Object.getOwnPropertyDescriptor 设置 value（Vue/原生兼容）\n" +
            "  var descriptor = Object.getOwnPropertyDescriptor(\n" +
            "    window.HTMLTextAreaElement.prototype, 'value') ||\n" +
            "    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');\n" +
            "  if (descriptor && descriptor.set) {\n" +
            "    descriptor.set.call(textarea, msg);\n" +
            "  } else {\n" +
            "    textarea.value = msg;\n" +
            "  }\n" +
            "\n" +
            "  // 2c. 派发 input / change 事件\n" +
            "  ['input', 'change'].forEach(function(evName) {\n" +
            "    try {\n" +
            "      var ev = new Event(evName, { bubbles: true, cancelable: true });\n" +
            "      textarea.dispatchEvent(ev);\n" +
            "    } catch(e) {}\n" +
            "  });\n" +
            "\n" +
            "  // 2d. 尝试通过 InputEvent 再派发一次（部分 React 版本需要）\n" +
            "  try {\n" +
            "    if (typeof InputEvent !== 'undefined') {\n" +
            "      var ie = new InputEvent('input', {\n" +
            "        bubbles: true, cancelable: true, data: msg, inputType: 'insertText'\n" +
            "      });\n" +
            "      textarea.dispatchEvent(ie);\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "\n" +
            "  // ============ 3. 点击发送按钮 ============\n" +
            "  setTimeout(function() {\n" +
            "    var sendBtn = null;\n" +
            "\n" +
            "    // 策略1：DeepSeek 新版本 —— div[role=\"button\"].ds-button--primary\n" +
            "    var roleBtns = document.querySelectorAll('div[role=\"button\"]');\n" +
            "    for (var i = 0; i < roleBtns.length; i++) {\n" +
            "      var rb = roleBtns[i];\n" +
            "      var cls = rb.getAttribute('class') || '';\n" +
            "      // 找 primary/main 发送按钮（通常是蓝色/填充色的圆形按钮）\n" +
            "      if (cls.indexOf('ds-button--primary') !== -1 ||\n" +
            "          cls.indexOf('ds-button--filled') !== -1 ||\n" +
            "          cls.indexOf('_52c986b') !== -1) {\n" +
            "        sendBtn = rb;\n" +
            "        break;\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 策略2：按文本内容找（兼容旧版本页面）\n" +
            "    if (!sendBtn) {\n" +
            "      var all = document.querySelectorAll('button, a, [role=\"button\"], div[onclick]');\n" +
            "      for (var j = 0; j < all.length; j++) {\n" +
            "        var tt = (all[j].innerText || all[j].textContent || '').trim();\n" +
            "        if (tt && (tt.indexOf('发送') !== -1 || tt.indexOf('Send') !== -1)) {\n" +
            "          sendBtn = all[j];\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 策略3：键盘回车（最后兜底）\n" +
            "    if (!sendBtn) {\n" +
            "      try {\n" +
            "        var ke = new KeyboardEvent('keydown', {\n" +
            "          key: 'Enter', code: 'Enter', keyCode: 13,\n" +
            "          which: 13, bubbles: true, cancelable: true\n" +
            "        });\n" +
            "        textarea.dispatchEvent(ke);\n" +
            "        Android.log('DeepSeek: 回车键发送');\n" +
            "        return 'enter_sent';\n" +
            "      } catch(e2) {\n" +
            "        Android.onDeepSeekError('未找到发送按钮，回车发送也失败');\n" +
            "        return 'no_send_btn';\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    sendBtn.click();\n" +
            "    Android.log('DeepSeek: 已点击发送按钮');\n" +
            "    return 'clicked';\n" +
            "  }, 300);\n" +
            "\n" +
            "  return 'preparing';\n" +
            "})()";

		// 注册回调
		deepSeekReplyCallback = replyRef;
		deepSeekErrorCallback = errorRef;
		deepSeekLatch = latch;

		final Handler handler = mainHandler;
		if (handler == null) {
			errorRef.set("Handler 未初始化");
			latch.countDown();
			return;
		}

		handler.post(new Runnable() {
				@Override
				public void run() {
					if (boundWebView == null) {
						errorRef.set("WebView 已释放");
						latch.countDown();
						return;
					}

					// 先启动监听
					DeepSeekChatBridge.this.boundWebView.evaluateJavascript(observerScript, null);
// 再发送消息（分开调用）
					DeepSeekChatBridge.this.boundWebView.evaluateJavascript(sendScript, new ValueCallback<String>() {
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
                // 把 raw 当作 JSON 字符串来解析，构造一个 JSON 数组来解包
                // 这样才能真正去掉外层双引号并反转义内部字符
                String jsonArrayStr = "[" + raw + "]";
                org.json.JSONArray arr = new org.json.JSONArray(jsonArrayStr);
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
     * 创建新会话：在 DeepSeek 页面点击"新建对话"
     */
    public boolean newSession() {
        String js =
            "(function() {\n" +
            "  // 策略1: 直接通过 URL 跳转（最可靠）\n" +
            "  try {\n" +
            "    // DeepSeek 新对话通常在 /chat 路径\n" +
            "    var currentPath = location.pathname;\n" +
            "    if (currentPath.indexOf('/chat') === -1) {\n" +
            "      location.href = '/chat';\n" +
            "      return 'ok_navigate';\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "\n" +
            "  // 策略2: 查找含 '新建'/'新对话'/'new chat' 的按钮/元素\n" +
            "  var allClickable = document.querySelectorAll('button, a, [role=\"button\"], div[onclick]');\n" +
            "  for (var i = 0; i < allClickable.length; i++) {\n" +
            "    var el = allClickable[i];\n" +
            "    var txt = (el.innerText || el.textContent || '').trim().toLowerCase();\n" +
            "    var aria = (el.getAttribute('aria-label') || '').toLowerCase();\n" +
            "    var title = (el.getAttribute('title') || '').toLowerCase();\n" +
            "    if (txt.indexOf('新建') !== -1 || txt.indexOf('新对话') !== -1 || \n" +
            "        txt.indexOf('new chat') !== -1 || txt.indexOf('new conversation') !== -1 ||\n" +
            "        aria.indexOf('new chat') !== -1 || aria.indexOf('新建') !== -1 ||\n" +
            "        title.indexOf('new chat') !== -1 || title.indexOf('新建') !== -1) {\n" +
            "      // 优先选择侧边栏的元素（在左侧区域）\n" +
            "      var rect = el.getBoundingClientRect();\n" +
            "      if (rect.left < window.innerWidth * 0.3) {\n" +
            "        el.click();\n" +
            "        return 'ok_sidebar';\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 策略3: 查找加号图标（SVG 或 + 字符）\n" +
            "  var svgs = document.querySelectorAll('svg');\n" +
            "  for (var k = 0; k < svgs.length; k++) {\n" +
            "    var svg = svgs[k];\n" +
            "    var svgParent = svg.closest('button, a, [role=\"button\"], div');\n" +
            "    if (svgParent) {\n" +
            "      var sRect = svgParent.getBoundingClientRect();\n" +
            "      // 优先选择左侧的加号图标\n" +
            "      if (sRect.left < window.innerWidth * 0.3 && sRect.top < window.innerHeight * 0.3) {\n" +
            "        svgParent.click();\n" +
            "        return 'ok_svg_plus';\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 策略4: 查找包含 + 号的元素\n" +
            "  var allElements = document.querySelectorAll('*');\n" +
            "  for (var m = 0; m < allElements.length; m++) {\n" +
            "    var elem = allElements[m];\n" +
            "    if (elem.children && elem.children.length > 0) continue; // 只看叶子节点\n" +
            "    var text = (elem.textContent || '').trim();\n" +
            "    if (text === '+' || text === '＋') {\n" +
            "      var clickable = elem.closest('button, a, [role=\"button\"], div[onclick]');\n" +
            "      if (clickable) {\n" +
            "        var eRect = clickable.getBoundingClientRect();\n" +
            "        if (eRect.left < window.innerWidth * 0.3) {\n" +
            "          clickable.click();\n" +
            "          return 'ok_plus_char';\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 策略5: 查找侧边栏顶部第一个可点击元素（通常是新建按钮）\n" +
            "  var sidebar = document.querySelector('nav, aside, [class*=\"sidebar\"], [class*=\"side\"]');\n" +
            "  if (sidebar) {\n" +
            "    var firstBtn = sidebar.querySelector('button, a, [role=\"button\"]');\n" +
            "    if (firstBtn) {\n" +
            "      firstBtn.click();\n" +
            "      return 'ok_sidebar_first';\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 策略6: 查找 a 标签且 href 包含 chat\n" +
            "  var chatLinks = document.querySelectorAll('a[href*=\"chat\"]');\n" +
            "  for (var n = 0; n < chatLinks.length; n++) {\n" +
            "    var href = chatLinks[n].getAttribute('href') || '';\n" +
            "    // 排除具体的会话链接，找根路径的\n" +
            "    if (href === '/chat' || href === '/a/chat' || href.endsWith('/chat')) {\n" +
            "      chatLinks[n].click();\n" +
            "      return 'ok_chat_link';\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // 策略7: 兜底 - 直接修改 URL\n" +
            "  try {\n" +
            "    // 尝试跳转到新对话页面\n" +
            "    if (location.pathname !== '/chat' && location.pathname !== '/a/chat') {\n" +
            "      location.pathname = '/chat';\n" +
            "      return 'ok_path_change';\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "\n" +
            "  return 'not_found';\n" +
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
