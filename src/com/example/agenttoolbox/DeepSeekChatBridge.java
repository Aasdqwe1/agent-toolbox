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
        // 基于实际 DeepSeek 页面 DOM：
        //   - AI 消息内容: .ds-assistant-message-main-content
        //   - 操作栏（回复完成标志）: .ds-button--iconLabelTertiary
        //   - 发送按钮: div[role="button"].ds-button--primary（向上箭头 SVG）
        //   - 暂停按钮: div[role="button"].ds-button--circle（红色方块/暂停图标）
        //   - 打字指示器: [class*="typing"] / [class*="loading"]
        final String observerScript = "(function() {\n" +
            "  window.__deepseekReplyObserved = false;\n" +
            "  window.__deepseekLastReply = '';\n" +
            "  window.__deepseekPendingLatch = " + latch.hashCode() + ";\n" +
            "  window.__deepseekStartTs = Date.now();\n" +
            "\n" +
            "  // ======== A. 取最新一条 AI 消息的完整内容 ========\n" +
            "  function getLatestAssistantReply() {\n" +
            "    var aiMessages = document.querySelectorAll('.ds-assistant-message-main-content');\n" +
            "    // 兼容：如果没有 ds- 前缀类名，退回通用选择器\n" +
            "    if (aiMessages.length === 0) {\n" +
            "      aiMessages = document.querySelectorAll(\n" +
            "        '[class*=\"assistant-message\"]', '[class*=\"prose\"]', '.whitespace-pre-wrap',\n" +
            "        '[class*=\"markdown\"]', 'article', '[role=\"article\"]'\n" +
            "      );\n" +
            "    }\n" +
            "    if (aiMessages.length === 0) return null;\n" +
            "    var lastMsg = aiMessages[aiMessages.length - 1];\n" +
            "    var txt = (lastMsg.innerText || lastMsg.textContent || '').trim();\n" +
            "    return txt || null;\n" +
            "  }\n" +
            "\n" +
            "  // ======== B. 检查最新 AI 消息下方是否有操作栏（= 回复完成） ========\n" +
            "  function isLatestReplyComplete() {\n" +
            "    var aiMessages = document.querySelectorAll('.ds-assistant-message-main-content');\n" +
            "    if (aiMessages.length === 0) return false;\n" +
            "    var lastMsg = aiMessages[aiMessages.length - 1];\n" +
            "\n" +
            "    // 向上找消息容器（通常是父级或祖父级），再查里面是否有 iconLabelTertiary\n" +
            "    var container = lastMsg;\n" +
            "    for (var i = 0; i < 5 && container && container.parentElement; i++) {\n" +
            "      if (container.querySelector && container.querySelector('.ds-button--iconLabelTertiary')) {\n" +
            "        return true;\n" +
            "      }\n" +
            "      container = container.parentElement;\n" +
            "    }\n" +
            "    // 兼容：在整个文档末尾找 tertiary 按钮（可能是最新那条的）\n" +
            "    var terBtns = document.querySelectorAll('.ds-button--iconLabelTertiary');\n" +
            "    return terBtns.length > 0;\n" +
            "  }\n" +
            "\n" +
            "  // ======== C. 检查是否正在生成（有暂停按钮/发送按钮不是向上箭头） ========\n" +
            "  function isGenerating() {\n" +
            "    // 1) 打字/loading 指示器\n" +
            "    var typing = document.querySelector('[class*=\"typing\"]') ||\n" +
            "                  document.querySelector('[class*=\"loading\"]') ||\n" +
            "                  document.querySelector('[class*=\"thinking\"]');\n" +
            "    if (typing) return true;\n" +
            "\n" +
            "    // 2) 发送按钮位置显示「暂停/停止」图标（不是向上箭头）\n" +
            "    //    生成时：ds-button--circle 内的 SVG 显示红色方块 或 暂停图标\n" +
            "    //    空闲时：ds-button--primary 内的 SVG 显示向上箭头（发送）\n" +
            "    var sendBtn = document.querySelector('div[role=\"button\"][class*=\"ds-button--primary\"]');\n" +
            "    var circleBtns = document.querySelectorAll('div[role=\"button\"][class*=\"ds-button--circle\"]');\n" +
            "    // 有 circle 按钮但不是 primary（通常是暂停按钮）\n" +
            "    for (var j = 0; j < circleBtns.length; j++) {\n" +
            "      var cls = circleBtns[j].getAttribute('class') || '';\n" +
            "      if (cls.indexOf('ds-button--primary') === -1) {\n" +
            "        // 不是 primary 的 circle 按钮 → 暂停/停止按钮\n" +
            "        return true;\n" +
            "      }\n" +
            "    }\n" +
            "    // 3) 如果没有 primary 发送按钮 → 仍在生成\n" +
            "    if (!sendBtn) return true;\n" +
            "\n" +
            "    // 4) 分析 primary 发送按钮内的 SVG：是否仍有「不是向上箭头」的图标\n" +
            "    var svg = sendBtn.querySelector('svg');\n" +
            "    if (svg) {\n" +
            "      var svgHtml = svg.innerHTML || '';\n" +
            "      // 暂停图标：rect / 两条竖线；向上箭头：path 带 M...L... 向上三角\n" +
            "      if (svgHtml.indexOf('<rect') !== -1 ||\n" +
            "          svgHtml.indexOf('pause') !== -1 ||\n" +
            "          svgHtml.indexOf('stop') !== -1 ||\n" +
            "          svgHtml.indexOf('M 4.88') !== -1) {\n" +
            "        return true;\n" +
            "      }\n" +
            "    }\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // ======== D. 轮询 + MutationObserver 双通道监听 ========\n" +
            "  var pollCount = 0;\n" +
            "  var lastReply = '';\n" +
            "  var lastReplyLen = 0;\n" +
            "  var sameLenStable = 0;  // 内容长度连续稳定多少次 → 认为完成\n" +
            "\n" +
            "  function tryFinish() {\n" +
            "    var reply = getLatestAssistantReply();\n" +
            "    if (!reply || reply.length < 2) return false;\n" +
            "\n" +
            "    var complete = isLatestReplyComplete();\n" +
            "    var generating = isGenerating();\n" +
            "\n" +
            "    // 内容长度稳定检测（兜底：操作栏延迟出现时仍能捕获）\n" +
            "    if (reply.length === lastReplyLen) {\n" +
            "      sameLenStable++;\n" +
            "    } else {\n" +
            "      sameLenStable = 0;\n" +
            "      lastReplyLen = reply.length;\n" +
            "    }\n" +
            "\n" +
            "    // 判定完成：有操作栏 或 (不在生成中 且 内容长度已稳定 3 次)\n" +
            "    if (complete || (!generating && sameLenStable >= 3 && reply !== lastReply)) {\n" +
            "      if (reply === lastReply) return false;  // 重复不触发\n" +
            "      lastReply = reply;\n" +
            "      window.__deepseekLastReply = reply;\n" +
            "      window.__deepseekReplyObserved = true;\n" +
            "      if (window.__deepseekPollInterval) clearInterval(window.__deepseekPollInterval);\n" +
            "      if (window.__deepseekObserver) {\n" +
            "        try { window.__deepseekObserver.disconnect(); } catch(e) {}\n" +
            "      }\n" +
            "      Android.log('DeepSeek: 捕获回复（长度=' + reply.length + '，有操作栏=' + complete + '）');\n" +
            "      Android.onDeepSeekReply(reply);\n" +
            "      return true;\n" +
            "    }\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // 轮询（主通道，最可靠）\n" +
            "  window.__deepseekPollInterval = setInterval(function() {\n" +
            "    pollCount++;\n" +
            "    if (tryFinish()) return;\n" +
            "    // 最长 90 秒超时，超时返回当前已有内容\n" +
            "    if (pollCount > 180) {\n" +
            "      clearInterval(window.__deepseekPollInterval);\n" +
            "      if (window.__deepseekObserver) { try { window.__deepseekObserver.disconnect(); } catch(e) {} }\n" +
            "      var finalReply = getLatestAssistantReply() || window.__deepseekLastReply || '';\n" +
            "      if (finalReply && finalReply.length > 2) {\n" +
            "        Android.log('DeepSeek: 超时返回（长度=' + finalReply.length + '）');\n" +
            "        Android.onDeepSeekReply(finalReply);\n" +
            "      } else {\n" +
            "        Android.onDeepSeekError('超时未捕获到回复');\n" +
            "      }\n" +
            "    }\n" +
            "  }, 500);\n" +
            "\n" +
            "  // MutationObserver（辅助通道，加速捕获）\n" +
            "  window.__deepseekObserver = new MutationObserver(function(mutations) {\n" +
            "    // 每个变化快速检查，但真正决定完成仍依赖 tryFinish 的规则\n" +
            "    tryFinish();\n" +
            "  });\n" +
            "  var target = document.body || document.documentElement;\n" +
            "  if (target) {\n" +
            "    window.__deepseekObserver.observe(target,\n" +
            "      { childList: true, subtree: true, characterData: true, attributes: true });\n" +
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
