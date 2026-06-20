package com.example.agenttoolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.agenttoolbox.mcp.McpServer;

/**
 * DeepSeek 网页版集成 Activity
 * 功能：
 * - 加载 DeepSeek 网页版
 * - 检测登录状态
 * - 新建会话
 * - 刷新页面
 */
public class DeepSeekActivity extends Activity {

    private WebView webView;
    private TextView tvLoginStatus;
    private TextView tvStatus;
    private TextView tvMcpStatus;
    private Button btnBack;
    private Button btnNewChat;
    private Button btnRefresh;
    private Button btnExtractHtml;

    private Handler handler;
    private boolean isLoggedIn = false;
    private JavaScriptBridge jsBridge;
    private boolean isPageLoaded = false; // 页面是否已加载完成

    // DeepSeek 网址
    private static final String DEEPSEEK_URL = "https://chat.deepseek.com";
    private static final String DEEPSEEK_NEW_CHAT_URL = "https://chat.deepseek.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deepseek);

        handler = new Handler(Looper.getMainLooper());

        // 初始化视图
        initViews();

        // 初始化 WebView
        initWebView();

        // 加载 DeepSeek
        loadDeepSeek();
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        webView = (WebView) findViewById(R.id.webView);
        tvLoginStatus = (TextView) findViewById(R.id.tvLoginStatus);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvMcpStatus = (TextView) findViewById(R.id.tvMcpStatus);
        btnBack = (Button) findViewById(R.id.btnBack);
        btnNewChat = (Button) findViewById(R.id.btnNewChat);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnExtractHtml = (Button) findViewById(R.id.btnExtractHtml);

        // 返回按钮
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 新会话按钮
        btnNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newChat();
            }
        });

        // 刷新按钮
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                webView.reload();
                setStatus("正在刷新...");
            }
        });

        // 提取源码按钮
        btnExtractHtml.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                extractPageHtml();
            }
        });

        // 更新 MCP 状态
        updateMcpStatus();
    }

    /**
     * 初始化 WebView
     */
    private void initWebView() {
        WebSettings settings = webView.getSettings();

        // 启用 JavaScript
        settings.setJavaScriptEnabled(true);

        // 启用 DOM 存储
        settings.setDomStorageEnabled(true);

        // 启用数据库
        settings.setDatabaseEnabled(true);

        // 设置缓存模式
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 设置用户代理（模拟桌面浏览器，获得更好的体验）
        String userAgent = settings.getUserAgentString();
        settings.setUserAgentString(userAgent + " AgentToolbox/1.0");

        // 支持缩放
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // 自适应屏幕
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 启用混合内容（HTTP 和 HTTPS 混合）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // 初始化 JavaScriptBridge
        jsBridge = new JavaScriptBridge(this, webView);
        webView.addJavascriptInterface(jsBridge, "Android");
        jsBridge.setOnToolCallListener(new JavaScriptBridge.OnToolCallListener() {
            @Override
            public void onToolCallDetected(String toolName, String arguments) {
                setStatus("检测到工具调用: " + toolName);
            }

            @Override
            public void onToolResult(String toolName, String result) {
                setStatus("工具执行完成: " + toolName);
            }

            @Override
            public void onPageHtmlExtracted(String html, boolean success, String error) {
                if (success) {
                    copyToClipboard(html);
                    setStatus("页面源码已复制到剪贴板");
                    Toast.makeText(DeepSeekActivity.this, "页面源码已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } else {
                    String fullError = "【JavaScript 执行错误】\n" + (error != null ? error : "未知错误");
                    copyToClipboard(fullError);
                    setStatus("提取失败，错误信息已复制");
                    Toast.makeText(DeepSeekActivity.this, "提取失败，错误信息已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 设置 WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                setStatus("加载完成");

                // 延迟检测登录状态（等页面完全渲染）
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkLoginStatus();
                    }
                }, 1500);

                // 注入 MCP 工具监听脚本
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (jsBridge != null) {
                            jsBridge.injectObserverScript();
                            setStatus("MCP 监听已激活");
                        }
                    }
                }, 2000);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isPageLoaded = false; // 页面开始加载时重置标志
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 拦截特殊的 mcp:// 协议 URL，用于从 JS 传递数据到 Java
                if (url != null && url.startsWith("mcp://")) {
                    handleMcpUrl(url);
                    return true; // 拦截，不加载
                }

                // 在 WebView 内部加载所有链接
                view.loadUrl(url);
                return true;
            }
        });

        // 设置 WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) {
                    setStatus("加载中... " + newProgress + "%");
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                // 拦截特殊格式的 alert，用于从 JS 传递数据到 Java
                if (message != null && message.startsWith("MCP:")) {
                    handleMcpMessage(message.substring(4));
                    result.confirm();
                    return true; // 拦截，不显示弹窗
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        // 启用 Cookie
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    /**
     * 加载 DeepSeek
     */
    private void loadDeepSeek() {
        setStatus("正在加载 DeepSeek...");
        tvLoginStatus.setText("检测中...");
        webView.loadUrl(DEEPSEEK_URL);
    }

    /**
     * 新建会话
     */
    private void newChat() {
        if (!isLoggedIn) {
            Toast.makeText(this, "请先登录 DeepSeek", Toast.LENGTH_SHORT).show();
            return;
        }

        setStatus("正在新建会话...");

        // 尝试通过 JavaScript 点击新会话按钮
        // 如果失败，就重新加载页面
        webView.evaluateJavascript(
            "(function() {" +
            "  // 尝试找到新会话按钮并点击" +
            "  var newChatBtn = document.querySelector('[data-testid=\"new-chat-button\"]');" +
            "  if (newChatBtn) { newChatBtn.click(); return 'clicked'; }" +
            "  " +
            "  // 尝试其他选择器" +
            "  var buttons = document.querySelectorAll('button');" +
            "  for (var i = 0; i < buttons.length; i++) {" +
            "    if (buttons[i].textContent.includes('新对话') || buttons[i].textContent.includes('新建')) {" +
            "      buttons[i].click();" +
            "      return 'clicked';" +
            "    }" +
            "  }" +
            "  return 'not_found';" +
            "})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && value.contains("clicked")) {
                        setStatus("已新建会话");
                    } else {
                        // 备用方案：重新加载页面
                        loadDeepSeek();
                    }
                }
            }
        );
    }

    /**
     * 检测登录状态
     */
    private void checkLoginStatus() {
        // 方法1：通过 Cookie 检测
        final boolean hasLoginCookie = checkLoginCookie();

        // 方法2：通过页面元素检测
        webView.evaluateJavascript(
            "(function() {" +
            "  // 检查是否存在登录按钮" +
            "  var loginBtn = document.querySelector('[data-testid=\"login-button\"]');" +
            "  if (loginBtn) return 'not_logged_in';" +
            "  " +
            "  // 检查是否存在用户头像/用户菜单" +
            "  var userAvatar = document.querySelector('[data-testid=\"user-avatar\"]');" +
            "  if (userAvatar) return 'logged_in';" +
            "  " +
            "  // 检查 URL 是否包含登录相关路径" +
            "  if (window.location.pathname.includes('login')) return 'not_logged_in';" +
            "  " +
            "  // 检查是否有输入框（通常登录后才有聊天输入框）" +
            "  var chatInput = document.querySelector('textarea, [contenteditable=\"true\"]');" +
            "  if (chatInput) return 'logged_in';" +
            "  " +
            "  return 'unknown';" +
            "})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    boolean isLoggedInByPage = false;
                    if (value != null) {
                        if (value.contains("logged_in")) {
                            isLoggedInByPage = true;
                        } else if (value.contains("not_logged_in")) {
                            isLoggedInByPage = false;
                        } else {
                            // unknown 状态，用 cookie 判断
                            isLoggedInByPage = hasLoginCookie;
                        }
                    } else {
                        isLoggedInByPage = hasLoginCookie;
                    }

                    updateLoginStatus(isLoggedInByPage);
                }
            }
        );
    }

    /**
     * 通过 Cookie 检测登录状态
     */
    private boolean checkLoginCookie() {
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie(DEEPSEEK_URL);

        if (cookies == null || cookies.isEmpty()) {
            return false;
        }

        // 检查常见的登录相关 Cookie
        String[] loginCookies = {"token", "session", "auth", "user_id", "access_token"};
        for (String cookieName : loginCookies) {
            if (cookies.contains(cookieName + "=") || cookies.contains(cookieName.toUpperCase() + "=")) {
                return true;
            }
        }

        // DeepSeek 可能有特定的 Cookie 名称
        if (cookies.contains("deepseek_session") || cookies.contains("ds_token")) {
            return true;
        }

        return false;
    }

    /**
     * 更新登录状态显示
     */
    private void updateLoginStatus(boolean loggedIn) {
        isLoggedIn = loggedIn;

        if (loggedIn) {
            tvLoginStatus.setText("✓ 已登录");
            tvLoginStatus.setTextColor(0xFF0E6B4C); // 绿色
        } else {
            tvLoginStatus.setText("未登录");
            tvLoginStatus.setTextColor(0xFFB33A34); // 红色
        }
    }

    /**
     * 更新 MCP 服务状态
     */
    private void updateMcpStatus() {
        // 检查 MCP 服务是否在运行
        // 这里简单显示，实际可以通过绑定服务来获取状态
        boolean mcpRunning = McpServer.isServiceRunning();

        if (mcpRunning) {
            tvMcpStatus.setText("MCP: 运行中");
            tvMcpStatus.setTextColor(0xFF0E6B4C); // 绿色
        } else {
            tvMcpStatus.setText("MCP: 未启动");
            tvMcpStatus.setTextColor(0xFFB33A34); // 红色
        }
    }

    /**
     * 设置状态文本
     */
    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    /**
     * 处理 mcp:// 协议的 URL
     */
    private void handleMcpUrl(String url) {
        try {
            // 简单解析 URL 中的参数
            // 格式: mcp://action?param1=value1&param2=value2
            android.util.Log.d("DeepSeekDebug", "收到 mcp URL: " + url);
        } catch (Exception e) {
            android.util.Log.e("DeepSeekDebug", "处理 mcp URL 失败", e);
        }
    }

    /**
     * 处理通过 alert 传递的 MCP 消息
     */
    private void handleMcpMessage(String message) {
        try {
            android.util.Log.d("DeepSeekDebug", "收到 MCP 消息: " + message.substring(0, Math.min(message.length(), 100)));

            // 解析消息格式：MCP:{"success":true,"html":"..."} 或 MCP:{"success":false,"error":"..."}
            if (message.startsWith("{") && message.contains("\"success\"")) {
                if (message.contains("\"success\":true")) {
                    // 成功，提取 HTML
                    int htmlStart = message.indexOf("\"html\":\"") + 8;
                    int htmlEnd = message.lastIndexOf("\"}");
                    if (htmlStart > 0 && htmlEnd > htmlStart) {
                        String html = message.substring(htmlStart, htmlEnd);
                        // 处理转义字符
                        html = html.replace("\\\"", "\"")
                                   .replace("\\n", "\n")
                                   .replace("\\t", "\t")
                                   .replace("\\\\", "\\");

                        copyToClipboard(html);
                        setStatus("页面源码已复制到剪贴板");
                        Toast.makeText(this, "页面源码已复制到剪贴板", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else if (message.contains("\"success\":false")) {
                    // 失败，提取错误信息
                    int errorStart = message.indexOf("\"error\":\"") + 9;
                    int errorEnd = message.lastIndexOf("\"}");
                    if (errorStart > 0 && errorEnd > errorStart) {
                        String errorMsg = message.substring(errorStart, errorEnd);
                        errorMsg = errorMsg.replace("\\\"", "\"")
                                           .replace("\\n", "\n")
                                           .replace("\\t", "\t")
                                           .replace("\\\\", "\\");

                        String fullError = "【JavaScript 执行错误】\n" + errorMsg;
                        copyToClipboard(fullError);
                        setStatus("提取失败，错误信息已复制");
                        Toast.makeText(this, "提取失败，错误信息已复制", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }

            // 如果解析失败，把原始消息复制过去
            copyToClipboard("【MCP消息】\n" + message);
            setStatus("收到消息，已复制");
        } catch (Exception e) {
            String errorMsg = "【处理消息异常】\n" + e.getMessage() + "\n\n原始消息：\n" + message;
            copyToClipboard(errorMsg);
            setStatus("处理异常，错误信息已复制");
            Toast.makeText(this, "处理异常，错误信息已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 提取当前页面的 HTML 源码并复制到剪贴板
     * 使用 evaluateJavascript 直接返回结果（异步）
     */
    private void extractPageHtml() {
        // 检查 WebView 状态
        if (webView == null) {
            setStatus("错误：WebView 未初始化");
            copyToClipboard("【提取失败】\nWebView 未初始化，请重启应用");
            Toast.makeText(this, "WebView 异常，请重启应用", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查页面是否已加载
        if (!isPageLoaded) {
            setStatus("页面正在加载中，请稍候...");
            Toast.makeText(this, "页面尚未加载完成，请等待状态变为「加载完成」后再试", Toast.LENGTH_LONG).show();
            return;
        }

        setStatus("正在提取页面源码...");

        try {
            // 多策略提取 HTML：
            // 策略1：获取主文档的完整 HTML
            // 策略2：遍历所有 iframe，合并 iframe 内容
            // 策略3：获取 body 内的所有文本内容作为兜底
            // 策略4：获取 documentElement 的 innerHTML
            String js = "(function() {" +
                "  function esc(s) {" +
                "    return String(s)" +
                "      .replace(/\\\\/g, '\\\\\\\\')" +
                "      .replace(/\"/g, '\\\\\"')" +
                "      .replace(/\\n/g, '\\\\n')" +
                "      .replace(/\\r/g, '\\\\r')" +
                "      .replace(/\\t/g, '\\\\t');" +
                "  }" +
                "  " +
                "  function getIframeContents(doc) {" +
                "    var result = '';" +
                "    try {" +
                "      var iframes = doc.querySelectorAll ? doc.querySelectorAll('iframe') : [];" +
                "      for (var i = 0; i < iframes.length; i++) {" +
                "        try {" +
                "          var iframeDoc = iframes[i].contentDocument || (iframes[i].contentWindow && iframes[i].contentWindow.document);" +
                "          if (iframeDoc && iframeDoc.body && iframeDoc.body.innerHTML) {" +
                "            result += '\\n<!-- iframe ' + i + ' -->\\n' + iframeDoc.body.innerHTML;" +
                "          }" +
                "        } catch(e) { /* cross-origin iframe, skip */ }" +
                "      }" +
                "    } catch(e) {}" +
                "    return result;" +
                "  }" +
                "  " +
                "  var strategies = [];" +
                "  " +
                "  // 策略1：主文档完整 HTML（包含 doctype）" +
                "  try {" +
                "    var html = document.documentElement ? document.documentElement.outerHTML : '';" +
                "    var doctype = '';" +
                "    if (document.doctype) {" +
                "      doctype = '<!DOCTYPE ' + document.doctype.name;" +
                "      if (document.doctype.publicId) doctype += ' PUBLIC \\\"' + document.doctype.publicId + '\\\"';" +
                "      if (document.doctype.systemId) doctype += ' \\\"' + document.doctype.systemId + '\\\"';" +
                "      doctype += '>';" +
                "    }" +
                "    strategies.push({name:'main_doc', content: doctype + html, len: (doctype + html).length});" +
                "  } catch(e) { strategies.push({name:'main_doc', content:'', len:0, error: e.message}); }" +
                "  " +
                "  // 策略2：合并主文档 body + 所有 iframe 内容" +
                "  try {" +
                "    var bodyContent = document.body ? document.body.innerHTML : '';" +
                "    var iframeContent = getIframeContents(document);" +
                "    var combined = bodyContent + '\\n' + iframeContent;" +
                "    strategies.push({name:'body_iframe', content: combined, len: combined.length});" +
                "  } catch(e) { strategies.push({name:'body_iframe', content:'', len:0, error: e.message}); }" +
                "  " +
                "  // 策略3：documentElement innerHTML" +
                "  try {" +
                "    var inner = document.documentElement ? document.documentElement.innerHTML : '';" +
                "    strategies.push({name:'inner_html', content: inner, len: inner.length});" +
                "  } catch(e) { strategies.push({name:'inner_html', content:'', len:0, error: e.message}); }" +
                "  " +
                "  // 策略4：检查是否有对话内容（textarea/input 等输入区域）" +
                "  try {" +
                "    var chatEls = document.querySelectorAll('[contenteditable=\"true\"], textarea, [data-testid*=\"message\"], .message, .chat-message');" +
                "    var chatContent = '';" +
                "    for (var i = 0; i < chatEls.length; i++) {" +
                "      chatContent += '\\n' + chatEls[i].outerHTML;" +
                "    }" +
                "    strategies.push({name:'chat_elements', content: chatContent, len: chatContent.length});" +
                "  } catch(e) { strategies.push({name:'chat_elements', content:'', len:0, error: e.message}); }" +
                "  " +
                "  // 选择内容最丰富的策略" +
                "  var best = strategies[0];" +
                "  for (var i = 1; i < strategies.length; i++) {" +
                "    if (strategies[i].len > best.len) best = strategies[i];" +
                "  }" +
                "  " +
                "  if (best.len === 0) {" +
                "    return JSON.stringify({success: false, error: '页面内容为空，所有提取策略均失败', strategies: strategies});" +
                "  }" +
                "  " +
                "  return JSON.stringify({" +
                "    success: true," +
                "    strategy: best.name," +
                "    length: best.len," +
                "    html: best.content," +
                "    allStrategies: strategies" +
                "  });" +
                "})()";

            // 使用 evaluateJavascript 执行，直接在回调中获取返回值
            webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    handleExtractResult(value);
                }
            });

            // 设置超时，如果 10 秒内没有回调，提示可能失败
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (tvStatus.getText().toString().contains("正在提取")) {
                        setStatus("提取超时，请检查页面是否加载完成");
                        String errorMsg = "【提取超时】\n10秒内未收到结果\n可能原因：\n1. 页面内容加载较慢\n2. 页面安全限制\n3. 页面未加载完成\n\n建议：\n1. 等待页面完全加载后再提取\n2. 点击「刷新」后等待「加载完成」再重试";
                        copyToClipboard(errorMsg);
                        Toast.makeText(DeepSeekActivity.this, "提取超时，提示已复制", Toast.LENGTH_LONG).show();
                    }
                }
            }, 10000);

        } catch (Exception e) {
            String fullTrace = "【extractPageHtml 调用异常 - 完整堆栈】\n" +
                "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n" +
                "异常类型: " + e.getClass().getName() + "\n" +
                "异常信息: " + e.getMessage() + "\n\n堆栈跟踪:\n" +
                getStackTraceString(e) +
                "\n\n--- 原始错误 ---\n" + e.getClass().getName() + ": " + e.getMessage();
            copyToClipboard(fullTrace);
            setStatus("调用异常，详情已复制");
            Toast.makeText(this, "调用异常，完整堆栈已复制到剪贴板", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 处理提取源码的返回结果
     * evaluateJavascript 返回的值是一个带引号的字符串（JSON格式的字符串），
     * 需要先去掉外层引号，再解析其中的 JSON 对象
     */
    private void handleExtractResult(String value) {
        try {
            // 区分不同情况：null 表示 WebView 执行上下文无效，"" 表示返回值本身为空
            if (value == null) {
                setStatus("提取失败：WebView 执行上下文无效");
                copyToClipboard("【提取失败】\nWebView 执行上下文无效\n\n可能原因：\n1. 页面正在加载中\n2. 页面加载出错\n3. WebView 被销毁\n\n建议：\n1. 点击「刷新」按钮\n2. 等待状态显示「加载完成」后再提取");
                Toast.makeText(this, "提取失败，请刷新页面后重试", Toast.LENGTH_LONG).show();
                return;
            }

            if (value.isEmpty() || value.equals("null") || value.trim().isEmpty()) {
                setStatus("提取失败：返回值为空");
                copyToClipboard("【提取失败】\n返回值为空\n\n可能原因：\n1. 页面尚未完全加载\n2. 页面内容为空\n\n建议：\n1. 点击「刷新」按钮\n2. 等待状态显示「加载完成」后再提取\n3. 如持续失败，请检查网络连接");
                Toast.makeText(this, "提取失败，请刷新页面后重试", Toast.LENGTH_LONG).show();
                return;
            }

            // evaluateJavascript 返回的是 JavaScript 值的字符串表示，
            // 对于字符串会带双引号，因此需要先解析为字符串
            String jsonStr;
            try {
                // 尝试按 JSON 字符串解析（会去掉外层双引号，并还原转义字符）
                jsonStr = new org.json.JSONArray("[" + value + "]").getString(0);
            } catch (Exception parseEx) {
                // 如果解析失败，直接尝试去掉外层引号
                jsonStr = value;
                if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                    jsonStr = jsonStr.substring(1, jsonStr.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                            .replace("\\\\", "\\");
                }
            }

            // 解析返回的 JSON 对象
            org.json.JSONObject resultObj = new org.json.JSONObject(jsonStr);
            boolean success = resultObj.optBoolean("success", false);

            if (success) {
                String html = resultObj.optString("html", "");
                int length = resultObj.optInt("length", 0);
                String strategy = resultObj.optString("strategy", "unknown");

                if (html != null && !html.isEmpty()) {
                    copyToClipboard(html);
                    setStatus("已复制（" + length + " 字符，策略:" + strategy + "）");
                    Toast.makeText(this, "页面源码已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } else {
                    setStatus("提取失败：HTML 为空");
                    copyToClipboard("【提取失败】\n页面 HTML 内容为空（策略:" + strategy + "）\n\n建议：\n1. 刷新页面\n2. 确认 DeepSeek 页面正常显示");
                    Toast.makeText(this, "提取失败：页面内容为空", Toast.LENGTH_SHORT).show();
                }
            } else {
                String errorMsg = resultObj.optString("error", "未知错误");
                // 如果所有策略都失败，显示详细策略信息
                String debugInfo = "";
                try {
                    org.json.JSONArray strategies = resultObj.getJSONArray("allStrategies");
                    debugInfo = "\n\n各策略提取结果：\n";
                    for (int i = 0; i < strategies.length(); i++) {
                        org.json.JSONObject s = strategies.getJSONObject(i);
                        String sname = s.optString("name", "?");
                        int slen = s.optInt("len", 0);
                        String serr = s.optString("error", "");
                        debugInfo += "- " + sname + ": " + slen + " 字符";
                        if (!serr.isEmpty()) debugInfo += " [错误: " + serr + "]";
                        debugInfo += "\n";
                    }
                } catch (Exception ignored) { }

                String fullError = "【提取失败】\n" + errorMsg + debugInfo + "\n\n建议：\n1. 点击「刷新」重新加载页面\n2. 等待状态显示「加载完成」后再提取";
                copyToClipboard(fullError);
                setStatus("提取失败：" + errorMsg);
                Toast.makeText(this, "提取失败，详情已复制", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            String fullTrace = buildFullStackTrace(e, value);
            copyToClipboard(fullTrace);
            setStatus("处理异常，详情已复制");
            Toast.makeText(this, "处理异常，完整堆栈已复制到剪贴板", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 构建完整的异常堆栈信息，包含所有层级的 cause 和堆栈帧
     */
    private String buildFullStackTrace(Throwable t, String rawValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("【处理结果异常 - 完整堆栈】\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
        sb.append("\n");

        // 遍历异常链（包含所有 cause）
        int depth = 0;
        Throwable current = t;
        while (current != null && depth < 10) {
            if (depth > 0) {
                sb.append("\n--- Caused by: ").append(current.getClass().getName()).append(" ---\n");
            } else {
                sb.append("异常类型: ").append(t.getClass().getName()).append("\n");
            }

            // 异常消息
            sb.append("异常信息: ").append(current.getMessage() != null ? current.getMessage() : "(null)").append("\n");

            // 完整堆栈帧
            StackTraceElement[] stack = current.getStackTrace();
            if (stack != null && stack.length > 0) {
                sb.append("堆栈跟踪:\n");
                for (int i = 0; i < stack.length; i++) {
                    StackTraceElement frame = stack[i];
                    String className = frame.getClassName();
                    String methodName = frame.getMethodName();
                    String fileName = frame.getFileName();
                    int lineNumber = frame.getLineNumber();

                    // 高亮项目相关帧
                    String marker = className.contains("agenttoolbox") ? " >>>" : "";
                    sb.append(String.format("  at %s.%s(%s:%d)%s\n",
                        className.substring(className.lastIndexOf('.') + 1),
                        methodName,
                        fileName != null ? fileName : "Unknown",
                        lineNumber >= 0 ? lineNumber : 0,
                        marker));
                }
            }

            current = current.getCause();
            depth++;
        }

        sb.append("\n--- 原始返回值 ---\n");
        sb.append(rawValue != null ? rawValue : "(null)");

        return sb.toString();
    }

    /**
     * 获取异常的堆栈信息（简化版，保留向后兼容）
     */
    private String getStackTraceString(Exception e) {
        return buildFullStackTrace(e, null);
    }

    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("page_html", text);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取当前会话信息
     */
    public void getCurrentSession() {
        webView.evaluateJavascript(
            "(function() {" +
            "  // 获取当前会话 ID" +
            "  var path = window.location.pathname;" +
            "  var match = path.match(/\\/chat\\/([a-zA-Z0-9_-]+)/);" +
            "  if (match) return match[1];" +
            "  return 'new_chat';" +
            "})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && !value.equals("null")) {
                        setStatus("会话ID: " + value.replace("\"", ""));
                    }
                }
            }
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        updateMcpStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
