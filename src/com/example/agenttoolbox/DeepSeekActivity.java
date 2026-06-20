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
            "  // 策略1：点击导航栏中带 + 图标 / 新对话文本的按钮或可点击元素" +
            "  var candidates = document.querySelectorAll('button, [role=\"button\"], a, [class*=\"new-chat\" i]');" +
            "  for (var i = 0; i < candidates.length; i++) {" +
            "    var txt = (candidates[i].innerText || candidates[i].textContent || '').trim();" +
            "    if (txt.indexOf('新对话') === 0 || txt === '+' || txt === 'New chat' ||" +
            "        txt.indexOf('New') === 0 || txt.indexOf('新建') === 0) {" +
            "      candidates[i].click();" +
            "      return 'clicked';" +
            "    }" +
            "  }" +
            "  // 策略2：导航到主页自动开启新会话" +
            "  var link = document.querySelector('a[href=\"/\"]');" +
            "  if (link) { link.click(); return 'clicked'; }" +
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
     * 说明：DeepSeek 真实登录凭据存储在 localStorage（userToken / settingsJwt），
     * 页面中不存在 data-testid 属性，登录页面路径为 /sign_in。
     */
    private void checkLoginStatus() {
        webView.evaluateJavascript(
            "(function() {" +
            "  var result = {" +
            "    hasUserToken: false," +
            "    hasSettingsJwt: false," +
            "    isSignInPage: false," +
            "    hasChatInput: false," +
            "    path: window.location.pathname || ''" +
            "  };" +
            "  try {" +
            "    result.hasUserToken = !!(localStorage && localStorage.getItem('userToken'));" +
            "  } catch(e) {}" +
            "  try {" +
            "    result.hasSettingsJwt = !!(localStorage && localStorage.getItem('settingsJwt'));" +
            "  } catch(e) {}" +
            "  var p = (result.path || '').toLowerCase();" +
            "  result.isSignInPage = (p.indexOf('sign_in') >= 0 || p.indexOf('sign-in') >= 0 ||" +
            "                          p.indexOf('login') >= 0 || p.indexOf('sign-up') >= 0 ||" +
            "                          p.indexOf('sign_up') >= 0);" +
            "  try {" +
            "    result.hasChatInput = !!(document.querySelector('textarea') ||" +
            "                              document.querySelector('[contenteditable=\"true\"]'));" +
            "  } catch(e) {}" +
            "  return JSON.stringify(result);" +
            "})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    boolean loggedIn = false;
                    String detailInfo = "";
                    try {
                        String jsonStr = value;
                        if (jsonStr != null && jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                            jsonStr = jsonStr.substring(1, jsonStr.length() - 1)
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");
                        }
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        boolean hasUserToken = obj.optBoolean("hasUserToken", false);
                        boolean hasSettingsJwt = obj.optBoolean("hasSettingsJwt", false);
                        boolean isSignInPage = obj.optBoolean("isSignInPage", false);
                        boolean hasChatInput = obj.optBoolean("hasChatInput", false);
                        String path = obj.optString("path", "");
                        detailInfo = "path=" + path + " token=" + hasUserToken +
                                     " jwt=" + hasSettingsJwt + " input=" + hasChatInput;
                        // 判定逻辑：有 userToken 或 settingsJwt 且不在登录页 → 已登录
                        if ((hasUserToken || hasSettingsJwt) && !isSignInPage) {
                            loggedIn = true;
                        } else if (hasChatInput) {
                            loggedIn = true;
                        }
                    } catch (Exception e) {
                        android.util.Log.d("DeepSeekLogin", "解析登录检测结果失败: " + value, e);
                    }
                    updateLoginStatus(loggedIn, detailInfo);
                }
            }
        );
    }

    /**
     * 通过 Cookie 检测登录状态（保留兼容，不作为主判定依据）
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
     * 更新登录状态显示（带调试信息，供排查使用）
     */
    private void updateLoginStatus(boolean loggedIn) {
        updateLoginStatus(loggedIn, null);
    }

    private void updateLoginStatus(boolean loggedIn, String detail) {
        isLoggedIn = loggedIn;

        if (loggedIn) {
            tvLoginStatus.setText("✓ 已登录");
            tvLoginStatus.setTextColor(0xFF0E6B4C); // 绿色
            setStatus("检测完成 · 已登录" + (detail != null ? " (" + detail + ")" : ""));
        } else {
            tvLoginStatus.setText("未登录");
            tvLoginStatus.setTextColor(0xFFB33A34); // 红色
            setStatus("检测完成 · 未登录" + (detail != null ? " (" + detail + ")" : ""));
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
            // 第一步：先执行诊断探针，确认 WebView 执行上下文状态
            String probeJs = "(function() {" +
                "  var result = {" +
                "    hasDocument: !!(document)," +
                "    hasBody: !!(document && document.body)," +
                "    bodyLength: document && document.body ? document.body.innerHTML.length : 0," +
                "    docElementLength: document && document.documentElement ? document.documentElement.innerHTML.length : 0," +
                "    iframeCount: document ? document.querySelectorAll ? document.querySelectorAll('iframe').length : 0 : 0," +
                "    url: document ? document.URL || document.location.href : 'N/A'," +
                "    readyState: document ? document.readyState : 'N/A'" +
                "  };" +
                "  return JSON.stringify(result);" +
                "})()";

            webView.evaluateJavascript(probeJs, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String probeValue) {
                    runExtractionWithProbe(probeValue);
                }
            });

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
     * 诊断探针返回后，执行真正的 HTML 提取
     */
    private void runExtractionWithProbe(String probeValue) {
        final String probeInfo = probeValue;

        String js = "(function() {" +
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
            "        } catch(e) { result += '\\n<!-- iframe ' + i + ' error: ' + e.message + ' -->'; }" +
            "      }" +
            "    } catch(e) {}" +
            "    return result;" +
            "  }" +
            "  var strategies = [];" +
            "  try { var h = document.documentElement ? document.documentElement.outerHTML : ''; var d = ''; if (document.doctype) { d = '<!DOCTYPE ' + document.doctype.name; if (document.doctype.publicId) d += ' PUBLIC \\\"' + document.doctype.publicId + '\\\"'; if (document.doctype.systemId) d += ' \\\"' + document.doctype.systemId + '\\\"'; d += '>'; } strategies.push({name:'main_doc', content: d+h, len: (d+h).length}); } catch(e) { strategies.push({name:'main_doc', content:'', len:0, error: e.message}); }" +
            "  try { strategies.push({name:'body_iframe', content: (document.body?document.body.innerHTML:'') + getIframeContents(document), len: ((document.body?document.body.innerHTML:'') + getIframeContents(document)).length}); } catch(e) { strategies.push({name:'body_iframe', content:'', len:0, error: e.message}); }" +
            "  try { var inner = document.documentElement ? document.documentElement.innerHTML : ''; strategies.push({name:'inner_html', content: inner, len: inner.length}); } catch(e) { strategies.push({name:'inner_html', content:'', len:0, error: e.message}); }" +
            "  try { var els = document.querySelectorAll ? document.querySelectorAll('[contenteditable=\"true\"], textarea, [data-testid*=\"message\"], .message, .chat-message') : []; var c = ''; for (var i=0;i<els.length;i++) c += '\\n' + els[i].outerHTML; strategies.push({name:'chat_elements', content: c, len: c.length}); } catch(e) { strategies.push({name:'chat_elements', content:'', len:0, error: e.message}); }" +
            "  var best = strategies[0]; for (var i=1;i<strategies.length;i++) if (strategies[i].len > best.len) best = strategies[i];" +
            "  if (best.len === 0) return JSON.stringify({success: false, error: '页面内容为空，所有策略均失败', strategies: strategies});" +
            "  return JSON.stringify({success: true, strategy: best.name, length: best.len, html: best.content, allStrategies: strategies});" +
            "})()";

        webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                handleExtractResult(value, probeInfo);
            }
        });

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvStatus.getText().toString().contains("正在提取")) {
                    setStatus("提取超时");
                    String timeoutInfo = "【提取超时 - 完整诊断】\n" +
                        "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n\n" +
                        "=== 诊断探针返回值 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                        "=== 提取 JavaScript 未返回 ===\n" +
                        "12 秒内 evaluateJavascript 未回调，说明提取脚本未执行或被阻塞\n\n" +
                        "可能原因：\n" +
                        "1. 页面 DOM 结构异常庞大\n" +
                        "2. JavaScript 执行被阻塞\n" +
                        "3. 页面内容为空\n\n" +
                        "建议：\n" +
                        "1. 点击「刷新」重新加载\n" +
                        "2. 等待「加载完成」后再试\n" +
                        "3. 如果探针返回值显示页面正常，说明 JS 脚本执行超时";
                    copyToClipboard(timeoutInfo);
                    Toast.makeText(DeepSeekActivity.this, "超时，诊断信息已复制", Toast.LENGTH_LONG).show();
                }
            }
        }, 12000);
    }

    /**
     * 处理提取源码的返回结果（包含探针诊断信息）
     */
    private void handleExtractResult(String value, String probeValue) {
        String probeInfo = probeValue;

        try {
            if (value == null) {
                setStatus("提取失败：WebView 执行上下文无效");
                String fullInfo = "【提取失败 - WebView 执行上下文无效】\n" +
                    "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n\n" +
                    "=== 诊断探针返回值 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                    "=== 问题分析 ===\n" +
                    "evaluateJavascript 返回 null，说明 JavaScript 执行上下文无效\n\n" +
                    "探针含义：\n" +
                    "- hasDocument: 是否有 document 对象\n" +
                    "- hasBody: body 是否存在\n" +
                    "- bodyLength: body 内容长度\n" +
                    "- readyState: 页面加载状态\n\n" +
                    "可能原因：\n" +
                    "1. 页面正在加载中\n" +
                    "2. 页面加载出错（404/500 等）\n" +
                    "3. WebView 被销毁\n" +
                    "4. 跨域 CSP 限制\n\n" +
                    "建议：\n" +
                    "1. 点击「刷新」按钮重新加载\n" +
                    "2. 等待状态显示「加载完成」后再提取";
                copyToClipboard(fullInfo);
                Toast.makeText(this, "提取失败，完整诊断已复制", Toast.LENGTH_LONG).show();
                return;
            }

            if (value.isEmpty() || value.equals("null") || value.trim().isEmpty()) {
                setStatus("提取失败：返回值为空");
                String fullInfo = "【提取失败 - 返回值为空】\n" +
                    "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n\n" +
                    "=== 诊断探针返回值 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                    "=== 问题分析 ===\n" +
                    "JavaScript 返回了空值，说明页面内容确实为空\n\n" +
                    "建议：\n" +
                    "1. 点击「刷新」重新加载页面\n" +
                    "2. 确认 DeepSeek 页面正常加载显示\n" +
                    "3. 等待状态变为「加载完成」后再提取";
                copyToClipboard(fullInfo);
                Toast.makeText(this, "提取失败，完整诊断已复制", Toast.LENGTH_LONG).show();
                return;
            }

            String jsonStr;
            try {
                jsonStr = new org.json.JSONArray("[" + value + "]").getString(0);
            } catch (Exception parseEx) {
                jsonStr = value;
                if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                    jsonStr = jsonStr.substring(1, jsonStr.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                            .replace("\\\\", "\\");
                }
            }

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
                    String fullInfo = "【提取失败 - HTML 为空】\n" +
                        "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n" +
                        "策略: " + strategy + "\n\n" +
                        "=== 诊断探针 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                        "=== 各策略提取结果 ===\n" +
                        getStrategiesDebugInfo(resultObj) + "\n建议：刷新页面后重新提取";
                    copyToClipboard(fullInfo);
                    Toast.makeText(this, "提取失败，详情已复制", Toast.LENGTH_SHORT).show();
                }
            } else {
                String errorMsg = resultObj.optString("error", "未知错误");
                String fullInfo = "【提取失败 - JavaScript 执行出错】\n" +
                    "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n" +
                    "错误信息: " + errorMsg + "\n\n" +
                    "=== 诊断探针 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                    "=== 各策略提取结果 ===\n" +
                    getStrategiesDebugInfo(resultObj);
                copyToClipboard(fullInfo);
                setStatus("提取失败：" + errorMsg);
                Toast.makeText(this, "提取失败，完整诊断已复制", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            String fullTrace = "【handleExtractResult 异常 - 完整堆栈】\n" +
                "时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n" +
                "异常类型: " + e.getClass().getName() + "\n" +
                "异常信息: " + e.getMessage() + "\n\n" +
                "=== 诊断探针 ===\n" + (probeInfo != null ? probeInfo : "null") + "\n\n" +
                "=== 原始返回值 ===\n" + (value != null ? value : "null") + "\n\n" +
                "=== 完整堆栈 ===\n" + getStackTraceString(e);
            copyToClipboard(fullTrace);
            setStatus("处理异常，详情已复制");
            Toast.makeText(this, "处理异常，完整堆栈已复制", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 从 JSON 对象中提取各策略的调试信息
     */
    private String getStrategiesDebugInfo(org.json.JSONObject resultObj) {
        StringBuilder sb = new StringBuilder();
        try {
            org.json.JSONArray strategies = resultObj.getJSONArray("allStrategies");
            for (int i = 0; i < strategies.length(); i++) {
                org.json.JSONObject s = strategies.getJSONObject(i);
                String sname = s.optString("name", "?");
                int slen = s.optInt("len", 0);
                String serr = s.optString("error", "");
                sb.append(sname).append(": ").append(slen).append(" 字符");
                if (!serr.isEmpty()) sb.append(" [错误: ").append(serr).append("]");
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("(无法解析策略信息)\n");
        }
        return sb.toString();
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
