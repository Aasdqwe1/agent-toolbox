package com.example.agenttoolbox;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.agenttoolbox.mcp.McpServer;

import org.json.JSONObject;

/**
 * DeepSeek 面板管理器 — 封装 DeepSeek WebView 的生命周期、JS 注入、登录检测等全部逻辑。
 * 由 MainActivity 持有，不依赖独立 Activity，杜绝切后台被销毁的问题。
 */
public class DeepSeekPanelManager {

    private static final String DEEPSEEK_URL = "https://chat.deepseek.com";

    private final Context context;
    private final FrameLayout container;
    private final TextView tvLoginStatus;
    private final TextView tvStatus;
    private final TextView tvMcpStatus;
    private final View btnNewChat;
    private final View btnRefresh;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private JavaScriptBridge jsBridge;
    private boolean isLoggedIn = false;
    private boolean isPageLoaded = false;

    // 消息数监控
    private final Handler msgHandler = new Handler(Looper.getMainLooper());
    private Runnable msgChecker;
    private int lastMsgWarnLevel = 0;

    public DeepSeekPanelManager(Context ctx, FrameLayout container,
                                 TextView loginStatus, TextView status, TextView mcpStatus,
                                 View newChatBtn, View refreshBtn) {
        this.context = ctx;
        this.container = container;
        this.tvLoginStatus = loginStatus;
        this.tvStatus = status;
        this.tvMcpStatus = mcpStatus;
        this.btnNewChat = newChatBtn;
        this.btnRefresh = refreshBtn;
    }

    /** 初始化 WebView 并加载 DeepSeek（仅首次调用时创建） */
    public void open() {
        if (webView != null) {
            container.setVisibility(View.VISIBLE);
            return;
        }

        webView = new WebView(context);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        container.addView(webView);

        configureWebView(webView);
        DeepSeekChatBridge.getInstance().register(webView);

        setStatus("正在加载 DeepSeek...");
        tvLoginStatus.setText("检测中...");
        webView.loadUrl(DEEPSEEK_URL);
        container.setVisibility(View.VISIBLE);
    }

    /** 隐藏 DeepSeek 面板（不销毁 WebView） */
    public void close() {
        container.setVisibility(View.GONE);
    }

    /** 销毁 WebView */
    public void destroy() {
        if (msgChecker != null) msgHandler.removeCallbacks(msgChecker);
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        DeepSeekChatBridge.getInstance().detach();
    }

    // ========== WebView 配置 ==========

    private void configureWebView(WebView wv) {
        android.webkit.WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " AgentToolbox/1.0");
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        jsBridge = new JavaScriptBridge(context, wv);
        wv.addJavascriptInterface(jsBridge, "Android");
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
                    Toast.makeText(context, "页面源码已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } else {
                    copyToClipboard("【错误】\n" + (error != null ? error : "未知错误"));
                    setStatus("提取失败");
                }
            }
        });

        setupCallbacks(wv);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    private void setupCallbacks(final WebView wv) {
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                setStatus("加载完成");
                DeepSeekChatBridge.getInstance().markAsLoaded();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkLoginStatus();
                    }
                }, 1500);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                    if (jsBridge != null) {
                        jsBridge.injectObserverScript();
                        setStatus("MCP 监听已激活");
                    }
                } }, 2000);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isPageLoaded = false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("mcp://")) return true;
                view.loadUrl(url);
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) setStatus("加载中... " + newProgress + "%");
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                AppLogger.e("WebViewJS", "[Console][" + consoleMessage.messageLevel() + "] "
                        + consoleMessage.message() + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }
        });
    }

    // ========== 新会话 ==========

    public void newChat() {
        if (!isLoggedIn) {
            Toast.makeText(context, "请先登录 DeepSeek", Toast.LENGTH_SHORT).show();
            return;
        }
        if (webView == null) return;
        setStatus("正在新建会话...");
        webView.evaluateJavascript(
            "(function() {" +
            "  var cs = document.querySelectorAll('button, [role=\"button\"], a, [class*=\"new-chat\" i]');" +
            "  for (var i = 0; i < cs.length; i++) {" +
            "    var txt = (cs[i].innerText || '').trim();" +
            "    if (txt.indexOf('新对话') === 0 || txt === '+' || txt === 'New chat' || txt.indexOf('New') === 0 || txt.indexOf('新建') === 0) {" +
            "      cs[i].click(); return 'clicked';" +
            "    }" +
            "  }" +
            "  var link = document.querySelector('a[href=\"/\"]');" +
            "  if (link) { link.click(); return 'clicked'; }" +
            "  return 'not_found';" +
            "})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && value.contains("clicked")) {
                        setStatus("已新建会话");
                    } else {
                        webView.loadUrl(DEEPSEEK_URL);
                    }
                }
            }
        );
    }

    // ========== 登录检测 ==========

    private void checkLoginStatus() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "(function() {" +
            "  var r = {hasUserToken:false, hasSettingsJwt:false, isSignInPage:false, hasChatInput:false, path:window.location.pathname||''};" +
            "  try { r.hasUserToken = !!(localStorage && localStorage.getItem('userToken')); } catch(e){}" +
            "  try { r.hasSettingsJwt = !!(localStorage && localStorage.getItem('settingsJwt')); } catch(e){}" +
            "  var p = r.path.toLowerCase();" +
            "  r.isSignInPage = p.indexOf('sign_in')>=0 || p.indexOf('login')>=0 || p.indexOf('sign-up')>=0;" +
            "  try { r.hasChatInput = !!(document.querySelector('textarea') || document.querySelector('[contenteditable=\"true\"]')); } catch(e){}" +
            "  return JSON.stringify(r);" +
            "})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                boolean loggedIn = false;
                String detail = "";
                try {
                    String j = value;
                    if (j != null && j.startsWith("\"") && j.endsWith("\""))
                        j = j.substring(1, j.length()-1).replace("\\\"", "\"").replace("\\\\", "\\");
                    JSONObject o = new JSONObject(j);
                    boolean hasToken = o.optBoolean("hasUserToken", false);
                    boolean hasJwt = o.optBoolean("hasSettingsJwt", false);
                    boolean isSignIn = o.optBoolean("isSignInPage", false);
                    boolean hasInput = o.optBoolean("hasChatInput", false);
                    detail = "path=" + o.optString("path","") + " token=" + hasToken + " jwt=" + hasJwt + " input=" + hasInput;
                    if ((hasToken || hasJwt) && !isSignIn) loggedIn = true;
                    else if (hasInput) loggedIn = true;
                } catch (Exception ignored) {}
                updateLoginStatus(loggedIn, detail);
            }
        );
        } }

    private void updateLoginStatus(boolean loggedIn, String detail) {
        isLoggedIn = loggedIn;
        if (loggedIn) {
            tvLoginStatus.setText("✓ 已登录");
            tvLoginStatus.setTextColor(0xFF10B981);
            setStatus("检测完成 · 已登录" + (detail != null ? " (" + detail + ")" : ""));
        } else {
            tvLoginStatus.setText("未登录");
            tvLoginStatus.setTextColor(0xFFEF4444);
            setStatus("检测完成 · 未登录" + (detail != null ? " (" + detail + ")" : ""));
        }
    }

    // ========== MCP 状态 ==========

    /** 更新底部 MCP 状态（在 onResume 或定时调用） */
    public void updateMcpStatus() {
        boolean running = McpServer.isServiceRunning();
        if (running) {
            tvMcpStatus.setText("MCP: 运行中");
            tvMcpStatus.setTextColor(0xFF10B981);
        } else {
            tvMcpStatus.setText("MCP: 未启动");
            tvMcpStatus.setTextColor(0xFFEF4444);
        }
    }

    // ========== 消息数监控 ==========

    public void startMessageMonitor() {
        if (msgChecker != null) msgHandler.removeCallbacks(msgChecker);
        msgChecker = new Runnable() {
            @Override
            public void run() {
            if (webView == null) return;
            webView.evaluateJavascript(
                "((typeof processedMessages !== 'undefined') ? processedMessages.size : -1).toString()",
                new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value == null || value.equals("null") || value.equals("-1")) {
                        msgHandler.postDelayed(msgChecker, 30000);
                        return;
                    }
                    try {
                        int count = Integer.parseInt(value.replaceAll("\"", ""));
                        if (count > 80 && lastMsgWarnLevel < 2) {
                            lastMsgWarnLevel = 2;
                            setStatus("⚠️ 对话过长(" + count + "条)，建议新建会话");
                            tvLoginStatus.setText("⚠️ 对话过长");
                            tvLoginStatus.setTextColor(0xFFF59E0B);
                        } else if (count > 40 && lastMsgWarnLevel < 1) {
                            lastMsgWarnLevel = 1;
                            setStatus("对话已" + count + "条");
                        } else if (count <= 40 && lastMsgWarnLevel > 0) {
                            lastMsgWarnLevel = 0;
                            tvLoginStatus.setText(isLoggedIn ? "✓ 已登录" : "未登录");
                            tvLoginStatus.setTextColor(isLoggedIn ? 0xFF10B981 : 0xFFEF4444);
                        }
                    } catch (Exception ignored) {}
                    msgHandler.postDelayed(msgChecker, 30000);
                }
            );
        } } };
        msgHandler.postDelayed(msgChecker, 30000);
    }

    // ========== 工具 ==========

    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("deepseek", text));
    }
}
