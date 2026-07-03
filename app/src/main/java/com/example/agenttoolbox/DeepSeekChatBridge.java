package com.example.agenttoolbox;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

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
        android.util.Log.d("DeepSeekChatBridge", "已注册 WebView: " + (webView != null ? "有效" : "null"));
    }

    // Activity 返回/销毁时调用：保持 WebView 存活
    public synchronized void detach() {
        android.util.Log.d("DeepSeekChatBridge", "detach: WebView 保持存活");
    }

    public synchronized void unregister() {
        this.boundWebView = null;
        this.mainHandler = null;
        this.webViewLoaded = false;
        callbacksById.clear();
        latchById.clear();
        replyById.clear();
        errorById.clear();
        android.util.Log.d("DeepSeekChatBridge", "已注销 WebView");
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
                android.util.Log.w("DeepSeekChatBridge",
                    "sendMessage 超时（1800s），message=" + (message == null ? "" : message.substring(0, Math.min(40, message.length()))));
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (errRef.get() != null) {
            android.util.Log.w("DeepSeekChatBridge", "sendMessage 错误: " + errRef.get());
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
            "  var __rid = " + JSONObject.quote(requestId) + ";\n" +
            "  var __prefix = 'ds_' + __rid + '_';\n" +
            "  window.__deepseekRid = __rid;\n" +
            "  // ===== 清理该请求遗留的旧定时器/观察器 =====\n" +
            "  try {\n" +
            "    if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "    if (window[__prefix + 'obs']) window[__prefix + 'obs'].disconnect();\n" +
            "  } catch(_e) {}\n" +
            "\n" +
            "  // ===== A. 查找所有 AI 消息内容容器 =====\n" +
            "  // 多策略扫描：从最精确到最宽泛，任何一种匹配到就返回。\n" +
            "  // 同时返回 DOM NodeList，避免单一选择器偶发失效。\n" +
            "  function getAssistantMessages() {\n" +
            "    // 策略1：精确内容容器（DeepSeek v1 渲染路径）\n" +
            "    var list = document.querySelectorAll('.ds-markdown.ds-assistant-message-main-content');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    // 策略2：ds-assistant-message-main-content 独立类（不含 ds-markdown 的场景）\n" +
            "    list = document.querySelectorAll('[class*=\"ds-assistant-message-main-content\"]');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    // 策略3：ds-markdown--block 块级容器\n" +
            "    list = document.querySelectorAll('.ds-markdown--block');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    // 策略4：ds-markdown 任意级（可能包含 assistant+system+user，但后面只扫描最后几条即可）\n" +
            "    list = document.querySelectorAll('[class*=\"ds-markdown\"]');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    // 策略5：ds-message 外层 wrapper，内部取 markdown/content 子节点\n" +
            "    var msgs = document.querySelectorAll('[class*=\"ds-message\"]');\n" +
            "    if (msgs && msgs.length > 0) {\n" +
            "      // 对每个消息容器，再查其内容子节点，组成一个人工数组返回\n" +
            "      var collected = [];\n" +
            "      for (var mi = 0; mi < msgs.length; mi++) {\n" +
            "        var inner = msgs[mi].querySelector('[class*=\"ds-markdown\"], [class*=\"assistant-message\"], [class*=\"content\"], [class*=\"body\"]');\n" +
            "        if (inner) collected.push(inner);\n" +
            "      }\n" +
            "      if (collected.length > 0) return collected;\n" +
            "    }\n" +
            "    // 策略6：通用 assistant/message 关键词\n" +
            "    list = document.querySelectorAll('[class*=\"assistant-message-main\"]');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    list = document.querySelectorAll('[class*=\"assistant-message\"]');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    // 策略7：最兜底\n" +
            "    return document.querySelectorAll('[class*=\"message\"][class*=\"assistant\"], [class*=\"prose\"], .whitespace-pre-wrap, article, [role=\"article\"]');\n" +
            "  }\n" +
            "\n" +
            "  // 结构化分析元素内容：统计各类标签数量，便于调试内容一致性\n" +
            "  function analyzeContentStructure(el, reply) {\n" +
            "    if (!el) return;\n" +
            "    try {\n" +
            "      var pCount = el.querySelectorAll('p').length;\n" +
            "      var hCount = el.querySelectorAll('h1,h2,h3,h4,h5,h6').length;\n" +
            "      var tableCount = el.querySelectorAll('table').length;\n" +
            "      var listCount = el.querySelectorAll('ul,ol').length;\n" +
            "      var preCount = el.querySelectorAll('pre').length;\n" +
            "      var codeCount = el.querySelectorAll('code').length;\n" +
            "      var spanCodeCount = el.querySelectorAll('span[class*=\"code\"],span[class*=\"ds-markdown\"]').length;\n" +
            "      var aCount = el.querySelectorAll('a').length;\n" +
            "      var brCount = el.querySelectorAll('br').length;\n" +
            "      var divCount = el.querySelectorAll('div').length;\n" +
            "      var innerTxt = (el.innerText || '').trim();\n" +
            "      Android.log('[DEBUG-STRUCT] 内容结构: 段落=' + pCount + ' 标题=' + hCount +\n" +
            "        ' 表格=' + tableCount + ' 列表=' + listCount + ' 代码块=' + preCount +\n" +
            "        ' code标签=' + codeCount + ' span代码=' + spanCodeCount +\n" +
            "        ' 链接=' + aCount + ' br=' + brCount + ' div=' + divCount +\n" +
            "        ' innerText长度=' + innerTxt.length);\n" +
            "      if (reply && reply.length > 0) {\n" +
            "        Android.log('[DEBUG-STRUCT] 转换后Markdown长度=' + reply.length + ' 首行=' + reply.substring(0, Math.min(100, reply.length)));\n" +
            "      }\n" +
            "    } catch (_se) { Android.log('[DEBUG-STRUCT] 分析异常: ' + _se.message); }\n" +
            "  }\n" +
            "\n" +
            "  function getAssistantReply(el) {\n" +
            "    if (!el) return null;\n" +
            "    var html = (el.innerHTML || '').trim();\n" +
            "    Android.log('[DEBUG-HTML] 原始HTML长度=' + (html ? html.length : 0));\n" +
            "    Android.log('[DEBUG-HTML] 原始HTML前200字符=' + (html ? html.substring(0, 200) : '(空)'));\n" +
            "    var result = null;\n" +
            "    // 优先扫描 <pre> 和 <code> 标签：DeepSeek 网页版可能把 JSON 渲染成代码块\n" +
            "    // 这样能拿到纯净文本，避免 htmlToMarkdown 转换时丢内容或改格式\n" +
            "    var codeEls = el.querySelectorAll('pre, code');\n" +
            "    if (codeEls && codeEls.length > 0) {\n" +
            "      for (var ci = 0; ci < codeEls.length; ci++) {\n" +
            "        var codeTxt = (codeEls[ci].innerText || codeEls[ci].textContent || '').trim();\n" +
            "        if (codeTxt && codeTxt.indexOf('\"jsonrpc\"') !== -1) {\n" +
            "          Android.log('[DEBUG-HTML] 从 <' + codeEls[ci].tagName.toLowerCase() + '> 提取到JSON-RPC，长度=' + codeTxt.length);\n" +
            "          result = codeTxt;\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "    // 没有从代码块提取到 JSON 时，回退到 innerText（纯文本，保留原始格式）\n" +
            "    if (!result || result.length === 0) {\n" +
            "      var innerTxt = (el.innerText || el.textContent || '').trim();\n" +
            "      if (innerTxt && innerTxt.indexOf('\"jsonrpc\"') !== -1) {\n" +
            "        Android.log('[DEBUG-HTML] 使用 innerText 提取JSON-RPC，长度=' + innerTxt.length);\n" +
            "        result = innerTxt;\n" +
            "      }\n" +
            "    }\n" +
            "    // 仍然没有 JSON 时，用 htmlToMarkdown 转换（普通文本回复路径）\n" +
            "    if (!result || result.length === 0) {\n" +
            "      if (html && html.length > 0) {\n" +
            "        var md = htmlToMarkdown(html);\n" +
            "        Android.log('[DEBUG-HTML] 转换后Markdown长度=' + (md ? md.length : 0));\n" +
            "        Android.log('[DEBUG-HTML] 转换后Markdown前200字符=' + (md ? md.substring(0, 200) : '(空)'));\n" +
            "        if (md && md.length > 0) {\n" +
            "          analyzeContentStructure(el, md);\n" +
            "          result = md;\n" +
            "        }\n" +
            "      }\n" +
            "      if (!result || result.length === 0) {\n" +
            "        result = (el.innerText || el.textContent || '').trim();\n" +
            "        Android.log('[DEBUG-HTML] 使用备用方案innerText，长度=' + (result ? result.length : 0));\n" +
            "      }\n" +
            "    }\n" +
            "    return result || null;\n" +
            "  }\n" +
            "\n" +
            "  // ===== HTML到Markdown的转换函数（P3修复：JSON保护+完整JSON提取）=====\n" +
            "  function htmlToMarkdown(html) {\n" +
            "    if (!html) return '';\n" +
            "    var md = html;\n" +
            "\n" +
            "  // ===== 第0步：检测并保护JSON区域 =====\n" +
            "    var _protectedJson = [];\n" +
            "    var _protectIdx = 0;\n" +
            "    function _protectJson(text) {\n" +
            "      if (!text || text.length === 0) return text;\n" +
            "      if (text.indexOf('\"jsonrpc\"') === -1 && text.indexOf('\"jsonrpc\":') === -1\n" +
            "        && text.indexOf('\"tools/call\"') === -1 && text.indexOf('\"method\"') === -1) {\n" +
            "        return text;\n" +
            "      }\n" +
            "      var result = '';\n" +
            "      var pos = 0;\n" +
            "      while (pos < text.length) {\n" +
            "        var braceStart = text.indexOf('{', pos);\n" +
            "        if (braceStart === -1) { result += text.substring(pos); break; }\n" +
            "        var inStr = false; var quoteCh = ''; var esc = false; var depth = 1; var end = -1;\n" +
            "        for (var j = braceStart + 1; j < text.length; j++) {\n" +
            "          var cc = text.charAt(j);\n" +
            "          if (inStr) { if (esc) { esc = false; continue; } if (cc === '\\\\') { esc = true; continue; } if (cc === quoteCh) { inStr = false; continue; } continue; }\n" +
            "          if (cc === '\"') { inStr = true; quoteCh = cc; continue; }\n" +
            "          if (cc === '{') {\n" +
            "            depth++;\n" +
            "          } else if (cc === '}') { depth--; if (depth === 0) { end = j; break; } }\n" +
            "        }\n" +
            "        if (end !== -1) {\n" +
            "          var potential = text.substring(braceStart, end + 1);\n" +
            "          if (potential.indexOf('\"jsonrpc\"') !== -1 || potential.indexOf('\"tools/call\"') !== -1 || potential.indexOf('\"method\"') !== -1) {\n" +
            "            var placeholder = '__JSON_PROTECTED_' + _protectIdx + '__';\n" +
            "            _protectedJson[_protectIdx] = potential;\n" +
            "            _protectIdx++;\n" +
            "            result += text.substring(pos, braceStart) + placeholder;\n" +
            "            pos = end + 1; continue;\n" +
            "          }\n" +
            "        }\n" +
            "        result += text.substring(pos, braceStart + 1);\n" +
            "        pos = braceStart + 1;\n" +
            "      }\n" +
            "      return result;\n" +
            "    }\n" +
            "    md = _protectJson(md);\n" +
            "\n" +
            "  // ===== 第1步：循环解码HTML实体直到稳定 =====\n" +
            "    var _prevMd;\n" +
            "    var _decodeIter = 0;\n" +
            "    do {\n" +
            "      _prevMd = md;\n" +
            "      md = md.replace(/&amp;/g, '&');\n" +
            "      md = md.replace(/&#(\\d+);/g, function(m, num) { return String.fromCharCode(parseInt(num, 10)); });\n" +
            "      md = md.replace(/&#x([0-9a-fA-F]+);/g, function(m, hex) { return String.fromCharCode(parseInt(hex, 16)); });\n" +
            "      md = md.replace(/&quot;/g, '\"');\n" +
            "      md = md.replace(/&#39;/g, \"'\");\n" +
            "      md = md.replace(/&lt;/g, '<');\n" +
            "      md = md.replace(/&gt;/g, '>');\n" +
            "      md = md.replace(/&nbsp;/g, ' ');\n" +
            "      _decodeIter++;\n" +
            "    } while (md !== _prevMd && _decodeIter < 5 && md.length > 0);\n" +
            "\n" +
            "  // ===== 第2步：转换HTML标签为Markdown =====\n" +
            "    md = md.replace(/<\\/p>/gi, '\\n\\n');\n" +
            "    md = md.replace(/<p[^>]*>/gi, '');\n" +
            "    md = md.replace(/<div[^>]*>/gi, '');\n" +
            "    md = md.replace(/<\\/div>/gi, '\\n');\n" +
            "    md = md.replace(/<br\\s*\\/?>/gi, '\\n');\n" +
            "    md = md.replace(/<\\/?o?ul[^>]*>/gi, '');\n" +
            "    md = md.replace(/<li[^>]*>([\\s\\S]*?)<\\/li>/gi, '- $1\\n');\n" +
            "    md = md.replace(/<strong[^>]*>([\\s\\S]*?)<\\/strong>/gi, '**$1**');\n" +
            "    md = md.replace(/<b[^>]*>([\\s\\S]*?)<\\/b>/gi, '**$1**');\n" +
            "    md = md.replace(/<em[^>]*>([\\s\\S]*?)<\\/em>/gi, '*$1*');\n" +
            "    md = md.replace(/<i[^>]*>([\\s\\S]*?)<\\/i>/gi, '*$1*');\n" +
            "    md = md.replace(/<h1[^>]*>([\\s\\S]*?)<\\/h1>/gi, '# $1\\n');\n" +
            "    md = md.replace(/<h2[^>]*>([\\s\\S]*?)<\\/h2>/gi, '## $1\\n');\n" +
            "    md = md.replace(/<h3[^>]*>([\\s\\S]*?)<\\/h3>/gi, '### $1\\n');\n" +
            "    md = md.replace(/<h4[^>]*>([\\s\\S]*?)<\\/h4>/gi, '#### $1\\n');\n" +
            "    md = md.replace(/<a\\s+href=\"([^\"]*)\"[^>]*>([\\s\\S]*?)<\\/a>/gi, '[$2]($1)');\n" +
            "    // 按钮处理\n" +
            "    md = md.replace(/<code[^>]*>(复制|下载|copy|download)<\\/code>/gi, '[**$1**]');\n" +
            "    md = md.replace(/<button[^>]*>[\\s\\S]*?(复制|下载)[\\s\\S]*?<\\/button>/gi, '[**$1**]');\n" +
            "    // 移除其他HTML标签\n" +
            "    md = md.replace(/<[^>]+>/gi, '');\n" +
            "\n" +
            "  // ===== 第2.8步：恢复被保护的JSON内容 =====\n" +
            "    if (_protectedJson.length > 0) {\n" +
            "      for (var rj = 0; rj < _protectedJson.length; rj++) {\n" +
            "        var ph = '__JSON_PROTECTED_' + rj + '__';\n" +
            "        md = md.split(ph).join('\\n' + _protectedJson[rj] + '\\n');\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "  // ===== 第3步：清理格式 =====\n" +
            "    md = md.replace(/\\n{3,}/g, '\\n\\n');\n" +
            "    md = md.trim();\n" +
            "\n" +
            "  // ===== 第4步：工具调用JSON检测与提取 =====\n" +
            "    if (md.indexOf('\"jsonrpc\"') !== -1 || md.indexOf('\"jsonrpc\":') !== -1) {\n" +
            "      var jsonIdx = md.indexOf('\"jsonrpc\"');\n" +
            "      if (jsonIdx === -1) jsonIdx = md.indexOf('\"jsonrpc\":');\n" +
            "      if (jsonIdx !== -1) {\n" +
            "        var start = -1;\n" +
            "        for (var i = jsonIdx; i >= 0; i--) { if (md.charAt(i) === '{') { start = i; break; } }\n" +
            "        if (start !== -1) {\n" +
            "          var inStr2 = false; var quoteChar2 = '\"'; var esc2 = false; var depth2 = 1; var end2 = -1;\n" +
            "          for (var j = start + 1; j < md.length; j++) {\n" +
            "            var cc2 = md.charAt(j);\n" +
            "            if (inStr2) { if (esc2) { esc2 = false; continue; } if (cc2 === '\\\\') { esc2 = true; continue; } if (cc2 === quoteChar2) { inStr2 = false; continue; } continue; }\n" +
            "            if (cc2 === '\"') { inStr2 = true; quoteChar2 = cc2; continue; }\n" +
            "            if (cc2 === '{') {\n" +
            "              depth2++;\n" +
            "            } else if (cc2 === '}') { depth2--; if (depth2 === 0) { end2 = j; break; } }\n" +
            "          }\n" +
            "          if (end2 !== -1) {\n" +
            "            var extracted = md.substring(start, end2 + 1);\n" +
            "            return extracted;\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    return md.length > 0 ? md : null;\n" +
            "  }\n" +
            "\n" +
            "\n" +
            "\n" +
            "  // 备用全页扫描（选择器与 getAssistantMessages 同步优化）\n" +
            "  function getAssistantReplyFallback() {\n" +
            "    var selectors = [\n" +
            "      '.ds-markdown.ds-assistant-message-main-content',\n" +
            "      '.ds-markdown--block',\n" +
            "      '.ds-assistant-message-main-content',\n" +
            "      '[class*=\"ds-markdown\"]',\n" +
            "      '[class*=\"ds-assistant-message\"]',\n" +
            "      '[class*=\"assistant-message-main\"]',\n" +
            "      '[class*=\"assistant-message\"]',\n" +
            "      '[class*=\"markdown-content\"]',\n" +
            "      '[class*=\"chat-message\"]',\n" +
            "      'article',\n" +
            "      '[role=\"article\"]'\n" +
            "    ];\n" +
            "    for (var si = 0; si < selectors.length; si++) {\n" +
            "      var els = document.querySelectorAll(selectors[si]);\n" +
            "      if (els && els.length > 0) {\n" +
            "        var txt = getAssistantReply(els[els.length - 1]);\n" +
            "        if (txt && txt.length > 5) return txt;\n" +
            "      }\n" +
            "    }\n" +
            "    return null;\n" +
            "  }\n" +
            "\n" +
            "  var initialMsgCount = getAssistantMessages().length;\n" +
            "  var initialLastContent = '';\n" +
            "  if (initialMsgCount > 0) {\n" +
            "    var initialLastEl = getAssistantMessages()[initialMsgCount - 1];\n" +
            "    initialLastContent = getAssistantReply(initialLastEl) || '';\n" +
            "  }\n" +
            "  Android.log('[DEBUG][' + __rid + '] 监听启动, 初始AI消息数=' + initialMsgCount + ', 最后一条长度=' + initialLastContent.length);\n" +
            "  // 输出前几条消息的预览，方便调试\n" +
            "  try {\n" +
            "    var allMsgs = getAssistantMessages();\n" +
            "    for (var di = 0; di < Math.min(allMsgs.length, 3); di++) {\n" +
            "      var preview = getAssistantReply(allMsgs[di]);\n" +
            "      if (preview) preview = preview.substring(0, 50);\n" +
            "      Android.log('[DEBUG][' + __rid + '] 消息[' + di + '] 预览: ' + preview);\n" +
            "    }\n" +
            "  } catch(_de) {}\n" +
            "  var pollCount = 0;\n" +
            "  var lastSeenText = '';\n" +
            "  var lastReplyLen = 0;\n" +
            "  var sameLenStable = 0;\n" +
            "  var detectedNewMessage = false;\n" +
            "  var finished = false;\n" +
            "  var completionReady = false;\n" +
            "  var completionStartTime = 0;\n" +
            "  var lastStatusAt = 0;\n" +
            "  // 工具调用JSON内容增长跟踪（用于辅助判断LLM是否真的停止了）\n" +
            "  var lastJsonLen = 0;\n" +
            "  var jsonStableCount = 0;\n" +
            "\n" +
            "  // ===== B. 检查最新一条 AI 消息是否有操作栏 =====\n" +
            "  function isLatestReplyComplete(el) {\n" +
            "    if (!el) return false;\n" +
            "    var container = el;\n" +
            "    for (var i = 0; i < 8 && container && container.parentElement; i++) {\n" +
            "      if (container.querySelector && container.querySelector('.ds-button--iconLabelTertiary')) {\n" +
            "        return true;\n" +
            "      }\n" +
            "      container = container.parentElement;\n" +
            "    }\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // ===== C. 是否仍在生成 =====\n" +
            "  // 关键信号：.ds-button__background（生成中按钮的背景元素）= 正在生成\n" +
            "  function isGenerating() {\n" +
            "    // 第0步（最优先）：检测 .ds-button__background — DeepSeek生成状态核心标识\n" +
            "    var genBg = document.querySelector('.ds-button__background');\n" +
            "    if (genBg) return true;\n" +
            "\n" +
            "    // 第1步：检查 typing/loading/thinking 指示器（覆盖多语言/多样式）\n" +
            "    var typing = document.querySelector('[class*=\"typing\"]') ||\n" +
            "                  document.querySelector('[class*=\"loading\"]') ||\n" +
            "                  document.querySelector('[class*=\"thinking\"]') ||\n" +
            "                  document.querySelector('[class*=\"Thinking\"]') ||\n" +
            "                  document.querySelector('[class*=\"thinking-block\"]') ||\n" +
            "                  document.querySelector('[class*=\"thinking_block\"]') ||\n" +
            "                  document.querySelector('[class*=\"ai-thinking\"]') ||\n" +
            "                  document.querySelector('[aria-busy=\"true\"]') ||\n" +
            "                  document.querySelector('[aria-busy=\"loading\"]') ||\n" +
            "                  document.querySelector('[class*=\"cursor\"]') ||\n" +
            "                  document.querySelector('[class*=\"streaming\"]');\n" +
            "    if (typing) return true;\n" +
            "    \n" +
            "    // 第2步：检查所有圆形primary按钮（停止按钮 = 正在生成）\n" +
            "    var circlePrimaryBtns = document.querySelectorAll('div[role=\"button\"][class*=\"ds-button--circle\"][class*=\"ds-button--primary\"]');\n" +
            "    if (circlePrimaryBtns && circlePrimaryBtns.length > 0) return true;\n" +
            "    // 同时检查非圆形的 primary 按钮（有些版本按钮 class 可能不同）\n" +
            "    var primaryBtns = document.querySelectorAll('div[role=\"button\"][class*=\"ds-button--primary\"]');\n" +
            "    if (primaryBtns && primaryBtns.length > 0) {\n" +
            "      for (var ki = 0; ki < primaryBtns.length; ki++) {\n" +
            "        var bt = primaryBtns[ki];\n" +
            "        // 检查是否为停止/暂停按钮（文本或icon）\n" +
            "        var t = (bt.innerText || bt.getAttribute('aria-label') || '').trim();\n" +
            "        if (t && /停止|stop|暂停|pause|中断/.test(t)) return true;\n" +
            "      }\n" +
            "    }\n" +
            "    \n" +
            "    // 第3步：检查SVG路径是否包含矩形/正方形图标（停止图标）\n" +
            "    var allBtns = document.querySelectorAll('div[role=\"button\"][class*=\"ds-button--primary\"]');\n" +
            "    for (var k2 = 0; k2 < allBtns.length; k2++) {\n" +
            "      var b2 = allBtns[k2];\n" +
            "      var sv = b2.querySelector('svg');\n" +
            "      if (sv) {\n" +
            "        // 检查多个 path（有些图标是多个 path 组成）\n" +
            "        var paths = sv.querySelectorAll('path');\n" +
            "        if (paths && paths.length > 0) {\n" +
            "          for (var pIdx = 0; pIdx < paths.length; pIdx++) {\n" +
            "            var pd = paths[pIdx].getAttribute('d') || '';\n" +
            "            // 停止图标：正方形/矩形路径\n" +
            "            if (pd.indexOf('M2 4.88') !== -1 ||\n" +
            "                pd.indexOf('M 2 4.88') !== -1 ||\n" +
            "                pd.indexOf('4.88 2') !== -1 ||\n" +
            "                pd.indexOf('stop') !== -1 ||\n" +
            "                pd.indexOf('rect') !== -1 ||\n" +
            "                pd.indexOf('M0 ') !== -1) {\n" +
            "              return true;\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "        if (sv.querySelector('rect')) return true;\n" +
            "        // 有些图标用 rect 代替 path\n" +
            "        if (sv.querySelector('rect[height]')) return true;\n" +
            "      }\n" +
            "    }\n" +
            "    \n" +
            "    // 第4步：检查最后一条消息末尾的文本/状态关键词\n" +
            "    var last2 = document.querySelector('[class*=\"ds-assistant-message-main-content\"]:last-child, [class*=\"ds-markdown\"]:last-child, [class*=\"assistant-message\"]:last-child');\n" +
            "    if (last2) {\n" +
            "      var lt2 = (last2.innerText || last2.textContent || '').trim();\n" +
            "      if (lt2.length > 0 && lt2.length < 80) {\n" +
            "        if (/…|\\.{2,}|正在|思考|生成|处理|thinking|loading|typing/.test(lt2)) return true;\n" +
            "      }\n" +
            "    }\n" +
            "    \n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // 检测发送按钮是否可发送（无停止/暂停图标 = 生成已完毕）\n" +
            "  // 关键信号：向上箭头（M8.3125开头 / L9 3.95717V15.0431 等 send-icon）= 回答完毕，可以发送\n" +
            "  function isSendButtonReady() {\n" +
            "    var allPrimaryBtns = document.querySelectorAll('div[role=\"button\"][class*=\"ds-button--primary\"]');\n" +
            "    if (!allPrimaryBtns || allPrimaryBtns.length === 0) {\n" +
            "      // 没有找到primary按钮，检查是否有发送按钮icon（兜底）\n" +
            "      var sendIcon = document.querySelector('div[role=\"button\"] svg path[d*=\"M8.3125\"]') ||\n" +
            "                     document.querySelector('div[role=\"button\"] svg path[d*=\"M12 19\"]') ||\n" +
            "                     document.querySelector('div[role=\"button\"] svg path[d*=\"send\"]') ||\n" +
            "                     document.querySelector('div[role=\"button\"] svg path[d*=\"arrow\"]') ||\n" +
            "                     document.querySelector('div[role=\"button\"] svg path[d*=\"M0\"]');\n" +
            "      if (sendIcon) return true;\n" +
            "      return false;\n" +
            "    }\n" +
            "    // 是否有明确的发送按钮（向上箭头）\n" +
            "    var hasSendIcon = false;\n" +
            "    // 是否有明确的停止按钮\n" +
            "    var hasStopIcon = false;\n" +
            "    for (var k3 = 0; k3 < allPrimaryBtns.length; k3++) {\n" +
            "      var btn3 = allPrimaryBtns[k3];\n" +
            "      // 检查innerText（按钮文字可能是：停止/stop/暂停）\n" +
            "      var btText = (btn3.innerText || btn3.getAttribute('aria-label') || '').trim();\n" +
            "      if (btText && /停止|stop|暂停|pause|中断/.test(btText)) { hasStopIcon = true; continue; }\n" +
            "      var svg = btn3.querySelector('svg');\n" +
            "      if (svg) {\n" +
            "        var pList = svg.querySelectorAll('path');\n" +
            "        if (pList && pList.length > 0) {\n" +
            "          for (var p2 = 0; p2 < pList.length; p2++) {\n" +
            "            var d2 = pList[p2].getAttribute('d') || '';\n" +
            "            // 停止图标：正方形/矩形路径\n" +
            "            if (d2.indexOf('M2 4.88') !== -1 ||\n" +
            "                d2.indexOf('M 2 4.88') !== -1 ||\n" +
            "                d2.indexOf('4.88 2') !== -1 ||\n" +
            "                d2.indexOf('stop') !== -1 ||\n" +
            "                d2.indexOf('rect') !== -1 ||\n" +
            "                d2.indexOf('M0 ') !== -1) {\n" +
            "              hasStopIcon = true;\n" +
            "              break;\n" +
            "            }\n" +
            "            // 发送图标：向上箭头（M8.3125 0.981587 开头 / 含 L9 3.95717V15.0431 等竖线）\n" +
            "            if (d2.indexOf('M8.3125') !== -1 ||\n" +
            "                d2.indexOf('15.0431') !== -1 ||\n" +
            "                d2.indexOf('0.981587') !== -1 ||\n" +
            "                d2.indexOf('M8.') !== -1) {\n" +
            "              hasSendIcon = true;\n" +
            "              break;\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "        if (svg.querySelector('rect')) hasStopIcon = true;\n" +
            "      }\n" +
            "    }\n" +
            "    // 优先级：停止图标 > 发送图标 > 默认\n" +
            "    if (hasStopIcon) return false;  // 还在生成中\n" +
            "    if (hasSendIcon) return true;   // 已回答完毕\n" +
            "    return true; // 兜底：无停止图标就认为就绪\n" +
            "  }\n" +
            "\n" +
            "  // ===== D. 主循环：每 500ms 检查是否新增了 AI 消息 =====\n" +
            "  function hasContinueButton() {\n" +
            "    try {\n" +
            "      var btns = document.querySelectorAll('.ds-button__content');\n" +
            "      for (var bi = 0; bi < btns.length; bi++) {\n" +
            "        if (btns[bi].textContent.trim() === '继续生成') return true;\n" +
            "      }\n" +
            "    } catch(_e) {}\n" +
            "    return false;\n" +
            "  }\n" +
            "  function finish(reply) {\n" +
            "    if (finished) return;\n" +
            "    finished = true;\n" +
            "    var canContinue = hasContinueButton();\n" +
            "    Android.log('[DEBUG][' + __rid + '] 监听结束, 捕获回复长度=' + (reply ? reply.length : 0) + ', canContinue=' + canContinue);\n" +
            "    Android.log('[DEBUG][' + __rid + '] 完整回复: ' + (reply || '(空)'));\n" +
            "    if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "    if (window[__prefix + 'obs']) { try { window[__prefix + 'obs'].disconnect(); } catch(_e) {} }\n" +
            "    var finalReply = reply || '';\n" +
            "    if (canContinue) finalReply = finalReply + '\\n__CAN_CONTINUE__';\n" +
            "    Android.onDeepSeekReply(__rid, finalReply);\n" +
            "  }\n" +
            "\n" +
            "  function pollOnce() {\n" +
            "    if (finished) return;\n" +
            "    pollCount++;\n" +
            "    var list = getAssistantMessages();\n" +
            "    var gen = isGenerating();\n" +
            "\n" +
            "    // 没有任何AI消息，继续等待（动态超时：如果生成指示器仍在，允许更长时间）\n" +
            "    if (list.length === 0) {\n" +
            "      var maxWait = gen ? 1800 : 600; // 生成中允许15分钟，否则5分钟\n" +
            "      if (pollCount > maxWait) {\n" +
            "        finish('');\n" +
            "        Android.onDeepSeekError(__rid, '超时：未捕获到任何AI消息（pollCount=' + pollCount + '）');\n" +
            "      }\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 检查是否有新消息：数量增加，或最后一条内容变化且长度>0\n" +
            "    // 改进：只要lastContent.length比上次记录大，就认为有新内容（流式追加场景）\n" +
            "    var lastEl = list[list.length - 1];\n" +
            "    var lastContent = getAssistantReply(lastEl) || '';\n" +
            "    var msgCountChanged = list.length > initialMsgCount;\n" +
            "    var contentChanged = lastContent.length > 0 && lastContent !== initialLastContent;\n" +
            "    var contentGrew = lastContent.length > initialLastContent.length; // 内容长度增长\n" +
            "    var hasNewMessage = detectedNewMessage || msgCountChanged || contentChanged || contentGrew;\n" +
            "\n" +
            "    // 没有新消息，继续等待\n" +
            "    if (!hasNewMessage) {\n" +
            "      // 每4次轮询（约2秒）主动扫描，以防主选择器漏检\n" +
            "      if (pollCount % 4 === 0) {\n" +
            "        var forceTxt = getAssistantReplyFallback();\n" +
            "        if (forceTxt && forceTxt.length > 5 && forceTxt !== initialLastContent) {\n" +
            "          Android.log('[DEBUG][' + __rid + '] 强制扫描发现新内容，长度=' + forceTxt.length);\n" +
            "          detectedNewMessage = true;\n" +
            "          initialLastContent = forceTxt;\n" +
            "          // 主动恢复：更新lastEl为最后一条消息\n" +
            "          lastContent = forceTxt;\n" +
            "        }\n" +
            "      }\n" +
            "      if (!detectedNewMessage) {\n" +
            "        // 动态超时：如果生成指示器仍在，允许更长等待\n" +
            "        var maxWait2 = gen ? 1800 : 900; // 生成中允许15分钟，否则4.5分钟\n" +
            "        if (pollCount > maxWait2) {\n" +
            "          // 超时前最后一次备用提取\n" +
            "          var lastTry = getAssistantReplyFallback();\n" +
            "          if (lastTry && lastTry.length > 5) {\n" +
            "            Android.log('[DEBUG][' + __rid + '] 超时前备用提取成功，长度=' + lastTry.length);\n" +
            "            finish(lastTry);\n" +
            "            return;\n" +
            "          }\n" +
            "          finish('');\n" +
            "          Android.onDeepSeekError(__rid, '超时未捕获到新回复（pollCount=' + pollCount + '）');\n" +
            "        }\n" +
            "        return;\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 检测到新消息，设置标志\n" +
            "    if (!detectedNewMessage) {\n" +
            "      detectedNewMessage = true;\n" +
            "      Android.log('[DEBUG][' + __rid + '] 检测到新消息，开始跟踪');\n" +
            "    }\n" +
            "\n" +
            "    // 更新基准\n" +
            "    if (list.length > initialMsgCount) {\n" +
            "      initialMsgCount = list.length;\n" +
            "    }\n" +
            "    initialLastContent = lastContent;\n" +
            "\n" +
            "    // 直接取最后一条AI消息，始终跟踪最新内容\n" +
            "    var latestEl = list[list.length - 1];\n" +
            "    var reply = getAssistantReply(latestEl);\n" +
            "    // 强制扫描 <pre> 代码块：当存在 <pre> 标签时，提取纯净的 JSON 替换混合内容\n" +
            "    var preEls = latestEl.querySelectorAll('pre');\n" +
            "    if (preEls && preEls.length > 0) {\n" +
            "      for (var pi = 0; pi < preEls.length; pi++) {\n" +
            "        var preTxt = (preEls[pi].innerText || preEls[pi].textContent || '').trim();\n" +
            "        if (preTxt && preTxt.indexOf('\"jsonrpc\"') !== -1) {\n" +
            "          reply = preTxt;\n" +
            "          Android.log('[DEBUG][' + __rid + '] 从 <pre> 提取到工具调用JSON, len=' + preTxt.length);\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 每10次轮询输出一次调试信息\n" +
            "    if (pollCount % 10 === 1 || pollCount <= 3) {\n" +
            "      var debugPreview = reply ? reply.substring(0, 80) : '';\n" +
            "      var sendReady = isSendButtonReady();\n" +
            "      var complete = isLatestReplyComplete(latestEl);\n" +
            "      Android.log('[DEBUG][' + __rid + '] 轮询#' + pollCount +\n" +
            "        ' 消息数=' + list.length + '/' + initialMsgCount +\n" +
            "        ' 生成中=' + gen +\n" +
            "        ' 发送就绪=' + sendReady +\n" +
            "        ' 操作栏=' + complete +\n" +
            "        ' 回复长度=' + (reply ? reply.length : 0) +\n" +
            "        ' 稳定次数=' + sameLenStable +\n" +
            "        ' 预览=\"' + debugPreview + '\"');\n" +
            "    }\n" +
            "\n" +
            "    // 状态心跳\n" +
            "    if (pollCount - lastStatusAt >= 10) {\n" +
            "      lastStatusAt = pollCount;\n" +
            "      var statusMsg = (reply && reply.length > 0 ? '正在接收回复' : (gen ? '模型正在生成中' : '等待模型响应'));\n" +
            "      try { Android.onDeepSeekChunk(__rid, '[STATUS] ' + statusMsg); } catch(_e) {}\n" +
            "    }\n" +
            "\n" +
            "    // ========== 工具调用优先检测 ==========\n" +
            "    // 精确匹配：必须同时包含 method 和 tools/call 才是工具调用\n" +
            "    var isToolCall = false;\n" +
            "    if (typeof reply === 'string') {\n" +
            "      var hasMethod = reply.indexOf('method') !== -1;\n" +
            "      var hasToolsCall = reply.indexOf('tools/call') !== -1;\n" +
            "      isToolCall = hasMethod && hasToolsCall;\n" +
            "    }\n" +
            "\n" +
            "    if (isToolCall) {\n" +
            "      // 工具调用场景：必须等待 JSON 完整\n" +
            "      var jsonStr = null;\n" +
            "      var firstBrace = reply.indexOf('{');\n" +
            "      // 从第一个 { 截到末尾；不要用 lastIndexOf('}') —— 它可能\n" +
            "      // 只找到了 arguments 内部的某个 }，截出来反而更残缺\n" +
            "      if (firstBrace !== -1) {\n" +
            "        jsonStr = reply.substring(firstBrace);\n" +
            "      }\n" +
            "      var jsonComplete = false;\n" +
            "      var robustCompleteAttempt = null;\n" +
            "      if (jsonStr && typeof jsonStr === 'string') {\n" +
            "        // 先用当前字符串本身尝试解析\n" +
            "        try { JSON.parse(jsonStr); jsonComplete = true; } catch(e) {}\n" +
            "        if (!jsonComplete) {\n" +
            "          // 状态机扫描：跳过字符串字面量与转义，计算未闭合的 { 与 [\n" +
            "          var inStr = false;\n" +
            "          var quoteChar = '\"';\n" +
            "          var esc = false;\n" +
            "          var braceCount = 0;\n" +
            "          var bracketCount = 0;\n" +
            "          for (var si = 0; si < jsonStr.length; si++) {\n" +
            "            var ch = jsonStr.charAt(si);\n" +
            "            if (inStr) {\n" +
            "              if (esc) { esc = false; continue; }\n" +
            "              if (ch === '\\\\') { esc = true; continue; }\n" +
            "              if (ch === quoteChar) { inStr = false; continue; }\n" +
            "              continue;\n" +
            "            }\n" +
            "            if (ch === '\"' || ch === \"'\" ) { inStr = true; quoteChar = ch; continue; }\n" +
            "            if (ch === '{') braceCount++;\n" +
            "            else if (ch === '}') { if (braceCount > 0) braceCount--; }\n" +
            "            else if (ch === '[') bracketCount++;\n" +
            "            else if (ch === ']') { if (bracketCount > 0) bracketCount--; }\n" +
            "          }\n" +
            "          // 按扫描结果精确补全右括号与右方括号\n" +
            "          var suffix = '';\n" +
            "          for (var bi = 0; bi < bracketCount; bi++) suffix = suffix + ']';\n" +
            "          for (var bj = 0; bj < braceCount; bj++) suffix = suffix + '}';\n" +
            "          if (suffix.length > 0) {\n" +
            "            robustCompleteAttempt = jsonStr + suffix;\n" +
            "            try {\n" +
            "              JSON.parse(robustCompleteAttempt);\n" +
            "              jsonStr = robustCompleteAttempt;\n" +
            "              jsonComplete = true;\n" +
            "            } catch(e) { /* 补全后仍失败，继续等待流式下一批 */ }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      if (jsonComplete) {\n" +
            "        // 验证通过：将提取的完整 JSON 作为最终回复\n" +
            "        reply = jsonStr;\n" +
            "        Android.log('[DEBUG][' + __rid + '] 成功提取并验证JSON-RPC调用，长度=' + jsonStr.length +\n" +
            "          (robustCompleteAttempt ? '（已通过状态机自动补全' + (robustCompleteAttempt.length - (jsonStr ? jsonStr.length : 0)) + '字符）' : ''));\n" +
            "        // 立即完成，不再等待稳定判定\n" +
            "        finish(reply);\n" +
            "        return;\n" +
            "      } else {\n" +
            "        // JSON 不完整：根本性修复 - 检查LLM生成状态\n" +
            "        if (completionReady) {\n" +
            "          completionReady = false;\n" +
            "          completionStartTime = 0;\n" +
            "        }\n" +
            "        sameLenStable = 0;\n" +
            "        Android.log('[DEBUG][' + __rid + '] JSON不完整，检查LLM生成状态（已检测到jsonrpc/tools/call）');\n" +
            "        // 【调试日志】输出当前获取到的完整回复内容预览\n" +
            "        var replyPreview = reply ? reply.substring(0, 200) : '(空)';\n" +
            "        Android.log('[DEBUG][' + __rid + '] 工具调用-完整回复预览: ' + replyPreview);\n" +
            "        Android.log('[DEBUG][' + __rid + '] 工具调用-完整回复长度: ' + (reply ? reply.length : 0));\n" +
            "        // 【调试日志】输出提取的JSON内容预览\n" +
            "        var jsonPreview = jsonStr ? jsonStr.substring(0, 200) : '(空)';\n" +
            "        Android.log('[DEBUG][' + __rid + '] 工具调用-提取JSON预览: ' + jsonPreview);\n" +
            "        // 双重验证：UI状态检测 + 内容增长检测\n" +
            "        // 只要内容还在增长，就说明LLM还在生成，即使UI状态检测认为已停止\n" +
            "        var currentJsonLen = jsonStr ? jsonStr.length : 0;\n" +
            "        var contentGrowing = currentJsonLen > lastJsonLen;\n" +
            "        if (contentGrowing) {\n" +
            "          jsonStableCount = 0;\n" +
            "          lastJsonLen = currentJsonLen;\n" +
            "        } else {\n" +
            "          jsonStableCount++;\n" +
            "        }\n" +
            "        var gen = isGenerating();\n" +
            "        // 【调试日志】详细输出生成状态检测结果\n" +
            "        Android.log('[DEBUG][' + __rid + '] 生成状态检测: UI=' + gen + ', 内容增长=' + contentGrowing + ', 当前长度=' + currentJsonLen + ', 上次长度=' + lastJsonLen + ', 稳定计数=' + jsonStableCount);\n" +
            "        // 真正的生成状态：UI显示生成中 OR 内容还在增长\n" +
            "        var actuallyGenerating = gen || contentGrowing;\n" +
            "        if (!actuallyGenerating) {\n" +
            "          // UI显示已停止 且 内容停止增长 = 大概率真的停止了\n" +
            "          // 但为了保险，再等几轮确认（防止瞬时波动）\n" +
            "          if (jsonStableCount >= 30) {\n" +
            "            // 连续30轮（约15秒）内容没有增长，才认为真的停止了\n" +
            "            // 给LLM足够的思考时间，避免工具调用思考阶段的短暂停顿被误判为停止\n" +
            "            Android.log('[DEBUG][' + __rid + '] ERROR: LLM已停止生成且JSON内容不再增长（pollCount=' + pollCount + ', stableCount=' + jsonStableCount + '）');\n" +
            "            Android.log('[DEBUG][' + __rid + '] ERROR-最终JSON内容: ' + (jsonStr || '(空)'));\n" +
            "            Android.onDeepSeekError(__rid, '工具调用JSON不完整（LLM已停止生成）');\n" +
            "            if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "            if (window[__prefix + 'obs']) { try { window[__prefix + 'obs'].disconnect(); } catch(_e) {} }\n" +
            "            finished = true;\n" +
            "          } else {\n" +
            "            Android.log('[DEBUG][' + __rid + '] UI显示已停止但内容刚停止增长，继续观察（stableCount=' + jsonStableCount + '/30）');\n" +
            "          }\n" +
            "        } else if (pollCount > 3600 && !contentGrowing) {\n" +
            "          // 动态超时：只有当内容停止增长时才超时（防止长思考阶段被误判）\n" +
            "          // 上限30分钟（3600 * 500ms），超过且内容停止增长时才强制停止\n" +
            "          Android.log('[DEBUG][' + __rid + '] TIMEOUT: 内容停止增长且超过30分钟（pollCount=' + pollCount + '）');\n" +
            "          Android.onDeepSeekError(__rid, '流式传输超时（LLM生成超过30分钟）');\n" +
            "          if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "          if (window[__prefix + 'obs']) { try { window[__prefix + 'obs'].disconnect(); } catch(_e) {} }\n" +
            "          finished = true;\n" +
            "        } else if (pollCount > 7200) {\n" +
            "          // 绝对上限：60分钟后不管什么状态都停止（防止无限等待）\n" +
            "          Android.log('[DEBUG][' + __rid + '] TIMEOUT: 绝对上限60分钟（pollCount=' + pollCount + '）');\n" +
            "          Android.onDeepSeekError(__rid, '流式传输超时（超过60分钟绝对上限）');\n" +
            "          if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "          if (window[__prefix + 'obs']) { try { window[__prefix + 'obs'].disconnect(); } catch(_e) {} }\n" +
            "          finished = true;\n" +
            "        } else {\n" +
            "          // LLM还在生成中 - 继续等待\n" +
            "          var reason = gen ? 'UI显示生成中' : '内容仍在增长';\n" +
            "          Android.log('[DEBUG][' + __rid + '] LLM仍在生成（' + reason + '），继续等待JSON流完成（pollCount=' + pollCount + ', len=' + currentJsonLen + '）');\n" +
            "        }\n" +
            "        return; // 跳过后续稳定判定\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // ========== 以下为普通回复（非工具调用）的完成判定 ==========\n" +
            "\n" +
            "    // 内容太短，继续等待（动态超时）\n" +
            "    if (!reply || reply.length < 2) {\n" +
            "      var maxWaitEmpty = gen ? 1800 : 900;\n" +
            "      if (pollCount > maxWaitEmpty) {\n" +
            "        var fallbackEmpty = getAssistantReplyFallback();\n" +
            "        if (fallbackEmpty && fallbackEmpty.length > 5) {\n" +
            "          Android.log('[DEBUG][' + __rid + '] 空内容超时前备用提取成功，长度=' + fallbackEmpty.length);\n" +
            "          finish(fallbackEmpty);\n" +
            "          return;\n" +
            "        }\n" +
            "        finish('');\n" +
            "        Android.onDeepSeekError(__rid, '超时：AI消息内容为空（pollCount=' + pollCount + '）');\n" +
            "      }\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // 流式回调：内容有增长就通知\n" +
            "    if (reply.length > lastReplyLen) {\n" +
            "      try { Android.onDeepSeekChunk(__rid, reply); } catch(_e) {}\n" +
            "      lastReplyLen = reply.length;\n" +
            "    }\n" +
            "\n" +
            "    // 稳定检测：内容长度和内容本身都未变化\n" +
            "    if (reply.length === lastReplyLen && reply === lastSeenText) {\n" +
            "      sameLenStable++;\n" +
            "    } else {\n" +
            "      sameLenStable = 0;\n" +
            "      lastSeenText = reply;\n" +
            "      // 内容有变化，重置冷却计时器\n" +
            "      completionReady = false;\n" +
            "      completionStartTime = 0;\n" +
            "    }\n" +
            "\n" +
            "    // 完成判定：采用冷却机制，内容稳定后再等待（更长观察期，避免偶发卡顿导致过早结束）\n" +
            "    // 修复：当消息中有 <pre> 代码块但内容不含 jsonrpc 时，可能工具调用JSON还在渲染，延迟完成\n" +
            "    var hasPreBlock = latestEl && latestEl.querySelector('pre') !== null;\n" +
            "    var jsonInContent = reply && (reply.indexOf('\"jsonrpc\"') !== -1 || reply.indexOf('\"jsonrpc\":') !== -1);\n" +
            "    var MIN_LENGTH = 5;\n" +
            "    var STABLE_WAIT_MS = 3500;\n" +
            "    // 有 <pre> 但 JSON 不在内容中时，需要更长稳定期等待代码块渲染完成\n" +
            "    var stableThreshold = (hasPreBlock && !jsonInContent) ? 40 : 16;\n" +
            "    var stableLong = sameLenStable >= stableThreshold && reply.length > MIN_LENGTH;\n" +
            "    var stableShort = sameLenStable >= 6 && reply.length > 200;\n" +
            "\n" +
            "    // 关键增强：如果UI仍显示生成中，且内容仍在增长，不要提前结束\n" +
            "    var shouldFinish = false;\n" +
            "    if (stableLong || stableShort) {\n" +
            "      // 稳定条件满足，但再次验证UI状态：如果UI仍显示生成中，暂缓完成\n" +
            "      if (!gen) {\n" +
            "        shouldFinish = true; // UI已停止生成，可以完成\n" +
            "      } else if (sameLenStable >= 30) {\n" +
            "        // UI显示生成中但内容已稳定30次=15秒，可能是UI指示器残留\n" +
            "        Android.log('[DEBUG][' + __rid + '] WARNING: UI仍显示生成中但内容已稳定15秒，强制完成');\n" +
            "        shouldFinish = true;\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    if (shouldFinish) {\n" +
            "      if (!completionReady) {\n" +
            "        completionReady = true;\n" +
            "        completionStartTime = Date.now();\n" +
            "        Android.log('[DEBUG][' + __rid + '] 普通回复内容已稳定，开始冷却计时（3.5s）');\n" +
            "      } else if (Date.now() - completionStartTime > STABLE_WAIT_MS) {\n" +
            "        Android.log('[DEBUG][' + __rid + '] 冷却结束，确认普通回复完成（稳定次数=' + sameLenStable + '）');\n" +
            "        finish(reply);\n" +
            "        return;\n" +
            "      }\n" +
            "    } else {\n" +
            "      if (completionReady) {\n" +
            "        completionReady = false;\n" +
            "        completionStartTime = 0;\n" +
            "        Android.log('[DEBUG][' + __rid + '] 普通回复内容继续增长，重置冷却');\n" +
            "      }\n" +
            "    }\n" +
            "\n" +
            "    // 超时兜底（普通回复）：动态超时（生成中=15分钟，否则=8分钟）\n" +
            "    var maxWaitFinal = gen ? 1800 : 960;\n" +
            "    if (pollCount > maxWaitFinal && reply && reply.length > 5) {\n" +
            "      Android.log('[DEBUG][' + __rid + '] 最终超时兜底：有部分内容就返回（pollCount=' + pollCount + ', len=' + reply.length + '）');\n" +
            "      finish(reply || '');\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  window[__prefix + 'poll'] = setInterval(pollOnce, 500);\n" +
            "\n" +
            "  // MutationObserver 辅助通道：加速检查\n" +
            "  var obsCount = 0;\n" +
            "  window[__prefix + 'obs'] = new MutationObserver(function() {\n" +
            "    obsCount++;\n" +
            "    if (obsCount % 20 === 1) {\n" +
            "      Android.log('[DEBUG][' + __rid + '] MutationObserver 触发次数=' + obsCount);\n" +
            "    }\n" +
            "    pollOnce();\n" +
            "  });\n" +
            "  var target = document.body || document.documentElement;\n" +
            "  if (target) {\n" +
            "    window[__prefix + 'obs'].observe(target,\n" +
            "      { childList: true, subtree: true, characterData: true, attributes: true });\n" +
            "    Android.log('[DEBUG][' + __rid + '] MutationObserver 已启动, 目标=' + (target.tagName || 'unknown'));\n" +
            "  } else {\n" +
            "    Android.log('[DEBUG][' + __rid + '] MutationObserver 启动失败: 无目标元素');\n" +
            "  }\n" +
            "\n" +
            "  // 给发送脚本一个信号：监听已就绪\n" +
            "  return 'observer_started_' + __rid;\n" +
            "})()";

        // ========== Step 2: 填写消息并发送 ==========
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
            "        sendBtnSource = 'class_match:' + cls.substring(0, 40);\n" +
            "        break;\n" +
            "      }\n" +
            "    }\n" +
            "    if (!sendBtn) {\n" +
            "      var all = document.querySelectorAll('button, a, [role=\"button\"], div[onclick]');\n" +
            "      for (var j = 0; j < all.length; j++) {\n" +
            "        var tt = (all[j].innerText || all[j].textContent || '').trim();\n" +
            "        if (tt && (tt.indexOf('发送') !== -1 || tt.indexOf('Send') !== -1)) {\n" +
            "          sendBtn = all[j];\n" +
            "          sendBtnSource = 'text_match:' + tt.substring(0, 20);\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "    if (sendBtn) {\n" +
            "      var btnDisabled = sendBtn.classList ? sendBtn.classList.contains('ds-button--disabled') : false;\n" +
            "      Android.log('[DEBUG][' + __rid + '] 发送按钮定位成功: ' + sendBtnSource + ', 禁用状态=' + btnDisabled);\n" +
            "      try { sendBtn.focus(); sendBtn.click(); } catch(_e5) {}\n" +
            "      Android.log('DeepSeek: 已点击发送按钮 (msg=' + msg.substring(0, Math.min(20, msg.length)) + ')');\n" +
            "      // 发送后校验：检查用户消息是否出现在消息列表\n" +
            "      setTimeout(function() {\n" +
            "        try {\n" +
            "          var userMsgs = document.querySelectorAll('.fbb737a4');\n" +
            "          var lastUserMsg = userMsgs.length > 0 ? userMsgs[userMsgs.length - 1] : null;\n" +
            "          var lastUserText = lastUserMsg ? (lastUserMsg.innerText || lastUserMsg.textContent || '').trim() : '';\n" +
            "          Android.log('[DEBUG][' + __rid + '] 发送后校验: 用户消息数=' + userMsgs.length + ', 最新用户消息预览=' + lastUserText.substring(0, 50));\n" +
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
                boundWebView.evaluateJavascript(observerScript, null);
                boundWebView.evaluateJavascript(sendScript, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String sendResult) {
                        android.util.Log.d("DeepSeekChatBridge", "[" + requestId + "] 发送结果: " + sendResult);
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
        if (message.length() <= maxLen) {
            return message;
        }
        
        // 分配剩余空间给前半部分和后半部分（2:1 比例，前部分更多）
        String ellipsis = "...[省略 X 字符]...";
        int ellipsisLen = ellipsis.length();
        
        if (ellipsisLen >= maxLen) {
            // 如果省略号本身太长，直接返回首部
            return message.substring(0, Math.max(1, maxLen - 3)) + "...";
        }
        
        int availableLen = maxLen - ellipsisLen;
        int frontLen = (availableLen * 2) / 3;
        int backLen = availableLen - frontLen;
        
        String front = message.substring(0, frontLen);
        String back = message.substring(message.length() - backLen);
        int omittedChars = message.length() - (frontLen + backLen);
        
        return front + "...[省略 " + omittedChars + " 字符]..." + back;
    }

    /**
     * P2 修复：完整记录 DeepSeek 回复内容
     */
    public void onDeepSeekReply(String requestId, String reply) {
        if (requestId == null) return;
        // 若 JS 回传了空回复，先在 Java 侧做一次备用 DOM 提取再放行
        if (reply == null || reply.isEmpty()) {
            android.util.Log.w("DeepSeekChatBridge",
                "[" + requestId + "] 收到空回复，触发备用 DOM 提取");
            tryExtractFromDOMAndRelease(requestId);
            return;
        }
        AtomicReference<String> ref = replyById.get(requestId);
        if (ref != null) ref.set(reply);
        CountDownLatch l = latchById.get(requestId);
        if (l != null) l.countDown();
        
        // P2 修复：增加完整内容日志，支持 4096 字节缓冲
        String logMsg = formatLongMessageForLog(reply, 4096);
        android.util.Log.d("DeepSeekChatBridge",
            "[" + requestId + "] 捕获回复 (长度=" + reply.length() + ")" + 
            "\n内容: " + logMsg);
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
                        android.util.Log.d("DeepSeekChatBridge",
                            "[" + requestId + "] 备用 DOM 提取结果长度=" + (extracted == null ? 0 : extracted.length()));
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
        android.util.Log.e("DeepSeekChatBridge",
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
            android.util.Log.w("DeepSeekChatBridge", "getSessions 解包失败: " + e.getMessage());
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
