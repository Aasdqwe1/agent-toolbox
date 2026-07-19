package com.example.agenttoolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
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
    private android.widget.FrameLayout webViewContainer;  // 容器 FrameLayout（id=R.id.webView）
    private TextView tvLoginStatus;
    private TextView tvStatus;
    private TextView tvMcpStatus;
    private Button btnBack;
    private Button btnNewChat;
    private Button btnRefresh;
    private Button btnExtractHtml;

    // 毛玻璃浮动按钮 + MCP 工具箱覆盖层
    private android.widget.FrameLayout mcpOverlay;
    private android.app.Dialog mcpDialog;
    private android.webkit.WebView mcpWebView;
    private android.widget.TextView btnCloseMcp;
    private android.widget.LinearLayout mcpTopBar;       // 顶部按钮栏（设置 + 关闭）
    private android.widget.TextView btnSettingsMcp;      // 设置按钮（透明度调节）
    private android.widget.LinearLayout mcpAlphaPanel;   // 透明度调节面板
    private android.widget.SeekBar mcpAlphaSeekbar;
    private android.widget.TextView mcpAlphaLabel;
    // MCP 工具箱透明度（跨 Activity 保持）：1.0=不透明，<1.0 可穿透显示底层 DeepSeek
    private static float sMcpAlpha = 1.0f;
    private TextView mcpFloatBtn;
    private float floatBtnX, floatBtnY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isLoggedIn = false;
    private JavaScriptBridge jsBridge;
    private boolean isPageLoaded = false; // 页面是否已加载完成

    // DeepSeek 网址（可通过 SharedPreferences 覆盖）
    private static final String DEFAULT_DEEPSEEK_URL = "https://chat.deepseek.com";
    private String deepSeekUrl = DEFAULT_DEEPSEEK_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deepseek);

        // 初始化视图（不含 WebView，WebView 从 Bridge 全局单例取）
        initViews();

        // 初始化或复用 WebView
        initOrReuseWebView();

        // 加载 DeepSeek（第一次才会实际 loadUrl，复用时跳过）
        loadDeepSeek();
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        webViewContainer = (android.widget.FrameLayout) findViewById(R.id.webView);
        tvLoginStatus = (TextView) findViewById(R.id.tvLoginStatus);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvMcpStatus = (TextView) findViewById(R.id.tvMcpStatus);
        btnBack = (Button) findViewById(R.id.btnBack);
        btnNewChat = (Button) findViewById(R.id.btnNewChat);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnExtractHtml = (Button) findViewById(R.id.btnExtractHtml);

        // 返回按钮 —— 回到主页（保持 WebView 在后台存活，HTTP API 继续可用）
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

        // MCP 工具箱浮动层初始化
        mcpOverlay = (android.widget.FrameLayout) findViewById(R.id.mcpOverlay);
        // 初始化或复用 MCP 工具箱 WebView（跨 Activity 保持，避免退出主页再打开刷新）
        initOrReuseMcpWebView();
        // 创建顶部按钮栏（容纳设置 + 关闭按钮）
        createMcpTopBar();
        // 创建程序化的关闭按钮（可拖动，同 MCP 样式）
        createCloseButton();
        // 创建设置按钮（调节透明度，可穿透显示 DeepSeek 内容）
        createSettingsButton();
        // 创建透明度调节面板（默认隐藏，点击设置按钮切换）
        createAlphaPanel();
        // 恢复上次透明度（跨 Activity 保持）
        applyMcpAlpha(sMcpAlpha);

        // 创建毛玻璃浮动按钮
        createFloatButton();

        // 启动消息数监控（每 30 秒检查一次页面消息量，超阈值提醒新建会话）
        startMessageMonitor();
    }

    // ============ 消息数监控（防止长对话卡顿） ============
    private android.os.Handler msgHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable msgChecker;
    private int lastMsgWarnLevel = 0; // 0=正常 1=建议 2=强烈建议

    private void startMessageMonitor() {
        if (msgChecker != null) msgHandler.removeCallbacks(msgChecker);
        msgChecker = new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                webView.evaluateJavascript(
                    "((typeof processedMessages !== 'undefined') ? processedMessages.size : -1).toString()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value == null || value.equals("null") || value.equals("-1")) {
                                msgHandler.postDelayed(msgChecker, 30000);
                                return;
                            }
                            try {
                                int count = Integer.parseInt(value.replaceAll("\"", ""));
                                if (count > 80) {
                                    if (lastMsgWarnLevel < 2) {
                                        lastMsgWarnLevel = 2;
                                        setStatus("⚠️ 对话过长(" + count + "条)，建议点「新会话」清理");
                                        tvLoginStatus.setText("⚠️ 对话过长");
                                        tvLoginStatus.setTextColor(getResources().getColor(R.color.warning));
                                        btnNewChat.setTextColor(0xFFEF4444); // 红色提醒
                                    }
                                } else if (count > 40) {
                                    if (lastMsgWarnLevel < 1) {
                                        lastMsgWarnLevel = 1;
                                        setStatus("对话已" + count + "条，过长时可点「新会话」");
                                    }
                                } else {
                                    if (lastMsgWarnLevel > 0) {
                                        lastMsgWarnLevel = 0;
                                        tvLoginStatus.setText(isLoggedIn ? "✓ 已登录" : "未登录");
                                        tvLoginStatus.setTextColor(getResources().getColor(
                                            isLoggedIn ? R.color.success : R.color.error));
                                        btnNewChat.setTextColor(getResources().getColor(R.color.accent));
                                    }
                                }
                            } catch (Exception ignored) {}
                            msgHandler.postDelayed(msgChecker, 30000);
                        }
                    }
                );
            }
        };
        msgHandler.postDelayed(msgChecker, 30000);
    }

    /** 新建会话时重置消息监控状态 */
    private void resetMessageMonitor() {
        lastMsgWarnLevel = 0;
    }

    // ============ 毛玻璃浮动按钮（可拖动，点击打开 MCP 工具箱） ============
    private void createFloatButton() {
        mcpFloatBtn = new TextView(this);
        mcpFloatBtn.setText("MCP工具箱");
        mcpFloatBtn.setTextSize(12f);
        mcpFloatBtn.setGravity(android.view.Gravity.CENTER);
        // 毛玻璃效果：半透明深色背景 + 圆角
        mcpFloatBtn.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            mcpFloatBtn.setElevation(8f);
            mcpFloatBtn.setBackground(mcpFloatBtn.getResources().getDrawable(android.R.drawable.ic_menu_compass));
        }
        mcpFloatBtn.setBackgroundColor(0xBB1E293B);
        // LayoutParams：固定在底部中央
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 16;
        lp.leftMargin = 16;
        webViewContainer.addView(mcpFloatBtn, lp);

        // 拖动逻辑
        mcpFloatBtn.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dx = event.getRawX() - v.getTranslationX();
                        dy = event.getRawY() - v.getTranslationY();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        v.setTranslationX(event.getRawX() - dx);
                        v.setTranslationY(event.getRawY() - dy);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        // 点击（拖动距离很小）时打开工具箱
                        if (Math.abs(event.getRawX() - dx - v.getTranslationX()) < 10
                                && Math.abs(event.getRawY() - dy - v.getTranslationY()) < 10) {
                            openMcpToolbox();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 创建可拖动的关闭按钮（同 MCP 按钮样式） */
    private void createCloseButton() {
        btnCloseMcp = new TextView(this);
        btnCloseMcp.setText("✕");
        btnCloseMcp.setTextSize(16f);
        btnCloseMcp.setTypeface(null, android.graphics.Typeface.BOLD);
        btnCloseMcp.setGravity(android.view.Gravity.CENTER);
        btnCloseMcp.setTextColor(0xFFFFFFFF);
        btnCloseMcp.setBackgroundColor(0xBB1E293B);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            btnCloseMcp.setElevation(8f);
        }
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 6;
        mcpTopBar.addView(btnCloseMcp, lp);

        btnCloseMcp.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dx = event.getRawX() - v.getTranslationX();
                        dy = event.getRawY() - v.getTranslationY();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        v.setTranslationX(event.getRawX() - dx);
                        v.setTranslationY(event.getRawY() - dy);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (Math.abs(event.getRawX() - dx - v.getTranslationX()) < 10
                                && Math.abs(event.getRawY() - dy - v.getTranslationY()) < 10) {
                            if (mcpDialog != null && mcpDialog.isShowing()) {
                                mcpDialog.dismiss();
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 创建顶部按钮栏容器（横向排列设置 + 关闭按钮，固定右上角） */
    private void createMcpTopBar() {
        mcpTopBar = new android.widget.LinearLayout(this);
        mcpTopBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        mcpTopBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
        lp.topMargin = 8;
        lp.rightMargin = 8;
        mcpOverlay.addView(mcpTopBar, lp);
    }

    /** 创建设置按钮（样式同关闭按钮，点击切换透明度面板） */
    private void createSettingsButton() {
        btnSettingsMcp = new TextView(this);
        btnSettingsMcp.setText("设置");
        btnSettingsMcp.setTextSize(13f);
        btnSettingsMcp.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSettingsMcp.setGravity(android.view.Gravity.CENTER);
        btnSettingsMcp.setTextColor(0xFFFFFFFF);
        btnSettingsMcp.setBackgroundColor(0xBB1E293B);
        int pad = dp(6);
        btnSettingsMcp.setPadding(pad, pad / 2, pad, pad / 2);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            btnSettingsMcp.setElevation(8f);
        }
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        // 插入到 index 0，使设置按钮位于关闭按钮左侧
        mcpTopBar.addView(btnSettingsMcp, 0, lp);

        btnSettingsMcp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mcpAlphaPanel == null) return;
                if (mcpAlphaPanel.getVisibility() == View.VISIBLE) {
                    mcpAlphaPanel.setVisibility(View.GONE);
                } else {
                    mcpAlphaPanel.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    /** 创建透明度调节面板（SeekBar，默认隐藏） */
    private void createAlphaPanel() {
        mcpAlphaPanel = new android.widget.LinearLayout(this);
        mcpAlphaPanel.setOrientation(android.widget.LinearLayout.VERTICAL);
        mcpAlphaPanel.setBackgroundColor(0xDD1E293B);
        int pad = dp(10);
        mcpAlphaPanel.setPadding(pad, pad / 2, pad, pad / 2);
        mcpAlphaPanel.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
        lp.topMargin = dp(52);   // 位于顶部按钮栏下方
        lp.rightMargin = 8;
        mcpOverlay.addView(mcpAlphaPanel, lp);

        mcpAlphaLabel = new TextView(this);
        mcpAlphaLabel.setText("透明度: " + (int) (sMcpAlpha * 100) + "%");
        mcpAlphaLabel.setTextSize(12f);
        mcpAlphaLabel.setTextColor(0xFFFFFFFF);
        mcpAlphaPanel.addView(mcpAlphaLabel);

        mcpAlphaSeekbar = new android.widget.SeekBar(this);
        mcpAlphaSeekbar.setMax(100);
        mcpAlphaSeekbar.setProgress((int) (sMcpAlpha * 100));
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                dp(180),
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        mcpAlphaPanel.addView(mcpAlphaSeekbar, slp);

        mcpAlphaSeekbar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float a = progress / 100f;
                // 限制最低 10%，避免完全看不见
                if (a < 0.1f) a = 0.1f;
                applyMcpAlpha(a);
                // label 用 clamp 后的值，保证与实际生效透明度一致
                mcpAlphaLabel.setText("透明度: " + (int) (a * 100) + "%");
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                // 松手时若低于下限，把滑块拉回 10%，与实际生效值一致
                if (seekBar.getProgress() < 10) {
                    seekBar.setProgress(10);
                }
            }
        });
    }

    /**
     * 应用 MCP 工具箱透明度。
     * a = 1.0：mcpWebView 不透明 + mcpOverlay 背景接近不透明黑，遮住底层 DeepSeek
     * a < 1.0：mcpWebView 半透明 + mcpOverlay 背景按比例衰减，可穿透看到底层 DeepSeek 网页
     *
     * 透明度全部由 mcpWebView.setAlpha + mcpOverlay 背景控制。
     * Dialog 窗口背景在创建时即设为全透明（见 openMcpToolbox），使窗口 Surface 支持透明，
     * 此处不再动态修改窗口背景——因为窗口 Surface 的透明性在添加到 WindowManager 时
     * 一次性确定，show 之后改背景 drawable 无法让不透明 Surface 变透明（会表现为
     * "必须先关闭再打开才生效"的 bug）。
     */
    private void applyMcpAlpha(float a) {
        sMcpAlpha = a;
        if (mcpWebView != null) {
            mcpWebView.setAlpha(a);
        }
        // mcpOverlay 遮罩背景：默认 #E6000000（0.9 黑），按 a 比例衰减
        // a=1.0 → 0xE5000000（接近不透明，挡住底层）；a=0.5 → 0x72000000（半透明）
        int bgAlpha = (int) (0.9f * a * 255);
        mcpOverlay.setBackgroundColor((bgAlpha << 24) & 0xFF000000);
    }

    /** dp 转 px */
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 打开 MCP 工具箱（用 Dialog 代替 overlay，避免覆盖 DeepSeek WebView 导致冻结） */
    private void openMcpToolbox() {
        if (mcpDialog == null) {
            mcpDialog = new android.app.Dialog(this);
            mcpDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            // 窗口背景创建时即设为全透明，使窗口 Surface 一开始就支持透明。
            // 窗口 Surface 的透明性在添加到 WindowManager 时一次性确定，
            // 若创建时设为不透明，show 后再改背景无法让 Surface 变透明。
            // 遮罩完全由 mcpOverlay 背景控制（applyMcpAlpha 按透明度衰减）。
            mcpDialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
            // 关闭窗口默认 dim，否则系统 dim 层会挡住底层 DeepSeek
            mcpDialog.getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // 将 mcpOverlay 从 Activity 布局移到 Dialog
            android.view.ViewParent parent = mcpOverlay.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(mcpOverlay);
            }
            mcpOverlay.setVisibility(View.VISIBLE);
            mcpDialog.setContentView(mcpOverlay);
            mcpDialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            mcpDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface dialog) {
                    setStatus("MCP 工具箱已关闭");
                }
            });
        }
        // 只首次加载 URL，后续打开不再刷新（保持页面状态）
        // 状态存于 Bridge 单例，跨 Activity 保持，避免退出主页再打开时刷新
        DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();
        if (!bridge.isMcpWebViewLoaded()) {
            String mcpUrl = buildMcpUrl();
            mcpWebView.loadUrl(mcpUrl);
            bridge.markMcpWebViewLoaded();
        }
        mcpDialog.show();
        // 应用透明度：mcpWebView/mcpOverlay 在 Activity 重建后是新实例，需重新应用 alpha 和遮罩背景
        applyMcpAlpha(sMcpAlpha);
    }

    /**
     * 初始化或复用 WebView
     * - 第一次进入：创建新 WebView → 配置 → 注册到 Bridge
     * - 再次进入：Bridge 已有 WebView → 从旧父容器 detach → attach 到新容器 → 跳过配置和加载（会话保持）
     */
    private void initOrReuseWebView() {
        DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();
        WebView existing = bridge.getBoundWebView();

        if (existing != null && bridge.isWebViewLoaded()) {
            // 复用：WebView 已在后台保持登录状态，只需 attach 到新容器
            AppLogger.d("DeepSeekActivity", "复用已存在的 WebView（保持会话）");
            webView = existing;

            // 从旧父容器 detach（可能）
            android.view.ViewParent oldParent = webView.getParent();
            if (oldParent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) oldParent).removeView(webView);
            }

            // attach 到新容器
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
            webViewContainer.addView(webView, lp);

            // 更新 WebViewClient 和 WebChromeClient 的引用到当前 Activity
            setupWebViewCallbacks(webView);

            setStatus("会话已恢复（保持登录状态）");
            isPageLoaded = true;
            // 立即刷新登录状态显示
            checkLoginStatus();
        } else {
            // 第一次进入：新建 WebView 并完整配置
            AppLogger.d("DeepSeekActivity", "创建新 WebView");
            webView = new WebView(getApplicationContext());

            // 添加到容器
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
            webViewContainer.addView(webView, lp);

            // 完整配置
            configureWebView(webView);

            // 注册到 Bridge
            bridge.register(webView);
        }
    }

    /**
     * 初始化或复用 MCP 工具箱 WebView
     * - 第一次进入：使用 layout 中的 mcpWebView → 配置 → 注册到 Bridge
     * - 再次进入：Bridge 已有 mcpWebView → 从旧父容器 detach → attach 到新 mcpOverlay → 跳过配置
     *
     * 与 {@link #initOrReuseWebView()} 同样的思路：把 WebView 交由 {@link DeepSeekChatBridge}
     * 单例持有，Activity 销毁时仅 detach 不 destroy，下次进入复用。
     * 是否需要 loadUrl 由 {@link #openMcpToolbox()} 根据 Bridge 中的 loaded 标志决定，
     * 这样即使用户上次没点开过工具箱就退出，本次进入也能正确加载，且不会重复刷新。
     */
    private void initOrReuseMcpWebView() {
        DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();
        WebView existing = bridge.getMcpWebView();

        if (existing != null) {
            // 复用：MCP WebView 已在 Bridge 中保持，只需 attach 到新容器
            AppLogger.d("DeepSeekActivity", "复用已存在的 MCP WebView（loaded=" + bridge.isMcpWebViewLoaded() + "）");
            mcpWebView = existing;

            // 从旧父容器 detach
            android.view.ViewParent oldParent = mcpWebView.getParent();
            if (oldParent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) oldParent).removeView(mcpWebView);
            }

            // 移除 layout 默认创建的 mcpWebView（避免内存泄漏）
            android.view.View defaultMcp = findViewById(R.id.mcpWebView);
            if (defaultMcp != null && defaultMcp != mcpWebView) {
                android.view.ViewParent dp = defaultMcp.getParent();
                if (dp instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) dp).removeView(defaultMcp);
                }
                ((android.webkit.WebView) defaultMcp).destroy();
            }
        } else {
            // 第一次进入：使用 layout 中的 mcpWebView 并完整配置
            AppLogger.d("DeepSeekActivity", "创建新 MCP WebView");
            mcpWebView = (android.webkit.WebView) findViewById(R.id.mcpWebView);
            // 配置 MCP 工具箱 WebView（开 JS、DOM 存储）
            android.webkit.WebSettings ms = mcpWebView.getSettings();
            ms.setJavaScriptEnabled(true);
            ms.setDomStorageEnabled(true);
            ms.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            // 注册到 Bridge，跨 Activity 保持
            bridge.registerMcpWebView(mcpWebView);
        }

        // attach 到当前 mcpOverlay（复用场景需要；首次场景 layout 已挂载，跳过）
        if (mcpWebView.getParent() != mcpOverlay) {
            android.widget.FrameLayout.LayoutParams mlp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
            // 插入到 index 0，确保关闭按钮（后 add）位于 mcpWebView 之上
            mcpOverlay.addView(mcpWebView, 0, mlp);
        }
    }

    private void configureWebView(WebView wv) {
        android.webkit.WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        String userAgent = settings.getUserAgentString();
        settings.setUserAgentString(userAgent + " AgentToolbox/1.0");
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // JavaScriptBridge
        jsBridge = new JavaScriptBridge(this, wv);
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
                    Toast.makeText(DeepSeekActivity.this, "页面源码已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } else {
                    String fullError = "【JavaScript 执行错误】\n" + (error != null ? error : "未知错误");
                    copyToClipboard(fullError);
                    setStatus("提取失败，错误信息已复制");
                    Toast.makeText(DeepSeekActivity.this, "提取失败，错误信息已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });

        setupWebViewCallbacks(wv);

        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(wv, true);
    }

    private void setupWebViewCallbacks(final WebView wv) {
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                setStatus("加载完成");

                // 标记 Bridge 中 WebView 已加载
                DeepSeekChatBridge.getInstance().markAsLoaded();

                // 注入可见性覆盖：防止 MCP 悬浮窗覆盖时 DeepSeek 页面冻结
                // 1. 覆盖 visibilityState/hidden → React 认为页面始终可见
                // 2. 拦截 visibilitychange 事件 → React 收不到隐藏通知
                // 3. 用 setTimeout 替代 requestAnimationFrame → 绕过 WebView 渲染暂停
                view.evaluateJavascript(
                    "try {" +
                    "  Object.defineProperty(document, 'visibilityState', {get:function(){return 'visible';}, configurable:true});" +
                    "  Object.defineProperty(document, 'hidden', {get:function(){return false;}, configurable:true});" +
                    "  Object.defineProperty(document, 'webkitVisibilityState', {get:function(){return 'visible';}, configurable:true});" +
                    "  Object.defineProperty(document, 'webkitHidden', {get:function(){return false;}, configurable:true});" +
                    "  document.addEventListener('visibilitychange', function(e){e.stopImmediatePropagation();}, true);" +
                    "  document.addEventListener('webkitvisibilitychange', function(e){e.stopImmediatePropagation();}, true);" +
                    "  window.requestAnimationFrame = function(cb) { return setTimeout(function() { cb(Date.now()); }, 16); };" +
                    "  window.cancelAnimationFrame = function(id) { clearTimeout(id); };" +
                    "  window.webkitRequestAnimationFrame = window.requestAnimationFrame;" +
                    "  window.webkitCancelAnimationFrame = window.cancelAnimationFrame;" +
                    "} catch(e){}", null);

                // 延迟检测登录状态
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() { checkLoginStatus(); }
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
            public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isPageLoaded = false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
                if (url != null && url.startsWith("mcp://")) {
                    handleMcpUrl(url);
                    return true;
                }
                view.loadUrl(url);
                return true;
            }
        });

        wv.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(android.webkit.WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) setStatus("加载中... " + newProgress + "%");
            }

            @Override
            public boolean onJsAlert(android.webkit.WebView view, String url, String message, android.webkit.JsResult result) {
                if (message != null && message.startsWith("MCP:")) {
                    handleMcpMessage(message.substring(4));
                    result.confirm();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }

            // 捕获 JavaScript 控制台错误，防止 JS 异常被静默吞掉
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String level = consoleMessage.messageLevel() != null
                    ? consoleMessage.messageLevel().toString() : "UNKNOWN";
                String msg = consoleMessage.message() != null ? consoleMessage.message() : "";
                String sourceId = consoleMessage.sourceId() != null ? consoleMessage.sourceId() : "";
                int line = consoleMessage.lineNumber();
                AppLogger.e("WebViewJS", "[Console][" + level + "] " + msg
                    + " (source: " + sourceId + ":" + line + ")");
                return true;
            }
        });
    }

    /**
     * 加载 DeepSeek（复用时跳过，保持原有会话）
     */
    private void loadDeepSeek() {
        DeepSeekChatBridge bridge = DeepSeekChatBridge.getInstance();
        if (bridge.isWebViewLoaded()) {
            AppLogger.d("DeepSeekActivity", "loadDeepSeek: 已加载过，跳过重新 loadUrl");
            return;
        }
        // 从 SharedPreferences 读取自定义 URL
        SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
        deepSeekUrl = prefs.getString("deepseek_url", DEFAULT_DEEPSEEK_URL);
        setStatus("正在加载 DeepSeek...");
        tvLoginStatus.setText("检测中...");
        webView.loadUrl(deepSeekUrl);
    }

    /**
     * 构建 MCP 悬浮窗访问地址（从服务端动态获取）
     */
    private String buildMcpUrl() {
        try {
            com.example.agenttoolbox.mcp.McpServer server = McpForegroundService.getInstance() != null
                ? McpForegroundService.getInstance().getMcpServer() : null;
            if (server != null) {
                String ip = server.getLocalIpAddress();
                int port = server.getPort();
                String bind = server.getBindAddress();
                // 如果绑定的是 127.0.0.1，就用 127.0.0.1
                if ("127.0.0.1".equals(bind)) {
                    return "http://127.0.0.1:" + port;
                }
                if (ip != null) {
                    return "http://" + ip + ":" + port;
                }
            }
        } catch (Exception ignored) {}
        // 回退：从 SharedPreferences 读取
        SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
        int port = prefs.getInt("port", 8080);
        return "http://127.0.0.1:" + port;
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

        if (webView == null) {
            setStatus("WebView 已销毁，请重启应用");
            return;
        }

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
        if (webView == null) {
            setStatus("WebView 已销毁，跳过登录检测");
            return;
        }
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
                        AppLogger.d("DeepSeekLogin", "解析登录检测结果失败: " + value, e);
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
        String cookies = cookieManager.getCookie(deepSeekUrl);

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
            tvLoginStatus.setTextColor(getResources().getColor(R.color.success));
            setStatus("检测完成 · 已登录" + (detail != null ? " (" + detail + ")" : ""));
        } else {
            tvLoginStatus.setText("未登录");
            tvLoginStatus.setTextColor(getResources().getColor(R.color.error));
            setStatus("检测完成 · 未登录" + (detail != null ? " (" + detail + ")" : ""));
        }
    }

    /**
     * 更新 MCP 服务状态
     */
    private void updateMcpStatus() {
        // 检查 MCP 服务是否在运行
        // 注意：DeepSeekActivity需要通过MainActivity启动服务后才能检测到
        boolean mcpRunning = McpServer.isServiceRunning();

        if (mcpRunning) {
            tvMcpStatus.setText("MCP: 运行中");
            tvMcpStatus.setTextColor(getResources().getColor(R.color.success));
            tvMcpStatus.setBackgroundResource(R.drawable.chip_on);
        } else {
            tvMcpStatus.setText("MCP: 未启动");
            tvMcpStatus.setTextColor(getResources().getColor(R.color.error));
            tvMcpStatus.setBackgroundResource(R.drawable.chip_off);
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
            AppLogger.d("DeepSeekDebug", "收到 mcp URL: " + url);
        } catch (Exception e) {
            AppLogger.e("DeepSeekDebug", "处理 mcp URL 失败", e);
        }
    }

    /**
     * 处理通过 alert 传递的 MCP 消息
     */
    private void handleMcpMessage(String message) {
        try {
            AppLogger.d("DeepSeekDebug", "收到 MCP 消息: " + message.substring(0, Math.min(message.length(), 100)));

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
        if (webView == null) {
            setStatus("WebView 已销毁");
            return;
        }
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // 正常回到主页（WebView 由 Bridge 全局持有，保持存活）
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (mcpWebView != null) mcpWebView.onResume();
        updateMcpStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不调用 webView.onPause()，保持 JavaScript 定时器运行
        // 否则 setInterval 轮询会被冻结，无法捕获 LLM 回复
        // 用户返回 MainActivity 时 MCP 服务仍需后台轮询
    }

    @Override
    protected void onDestroy() {
        // 清理所有 Handler 回调，防止 Activity 销毁后仍执行
        if (msgHandler != null && msgChecker != null) {
            msgHandler.removeCallbacks(msgChecker);
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (mcpDialog != null && mcpDialog.isShowing()) {
            mcpDialog.dismiss();
        }
        // 关键改动：Activity 销毁时不销毁 WebView
        // 仅从当前容器 detach，保持 WebView 存活 — HTTP API 继续可用，DeepSeek 会话保持登录
        if (webView != null) {
            android.view.ViewParent parent = webView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(webView);
            }
            DeepSeekChatBridge.getInstance().detach();  // 仅记录，不释放
        }
        // 同样保持 MCP 工具箱 WebView 存活：跨 Activity 复用，避免退出主页再打开时重新 loadUrl 刷新
        if (mcpWebView != null) {
            android.view.ViewParent mcpParent = mcpWebView.getParent();
            if (mcpParent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) mcpParent).removeView(mcpWebView);
            }
            DeepSeekChatBridge.getInstance().detachMcp();  // 仅记录，不释放
        }
        super.onDestroy();
    }
}
