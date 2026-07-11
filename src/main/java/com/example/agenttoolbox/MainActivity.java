package com.example.agenttoolbox;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.agenttoolbox.AppLogger;
import com.example.agenttoolbox.mcp.McpServer;

import java.io.File;
import java.util.Deque;
import java.util.LinkedList;

/**
 * 主Activity - MCP服务端控制界面
 */
public class MainActivity extends Activity {
    
    private TextView tvStatus;
    private TextView tvAddress;
    private TextView tvLog;
    private Button btnStart;
    private Button btnStop;
    private Button btnDeepSeek;
    private TextView statusChip;
    private View deepseekContainer;
    private View mainPanel;
    private DeepSeekPanelManager deepSeekPanel;

    
    private McpServer mcpServer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Deque<String> logDeque = new LinkedList<>();
    private static final int MAX_LOGS = 1000;  // 最多保存1000条日志
    
    private static final int PORT = 8080;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvAddress = (TextView) findViewById(R.id.tvAddress);
        tvLog = (TextView) findViewById(R.id.tvLog);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnStop = (Button) findViewById(R.id.btnStop);
        btnDeepSeek = (Button) findViewById(R.id.btnDeepSeek);
        statusChip = (TextView) findViewById(R.id.statusChip);
        deepseekContainer = findViewById(R.id.deepseekContainer);
        mainPanel = findViewById(R.id.mainPanel);

        // DeepSeek 面板管理器
        deepSeekPanel = new DeepSeekPanelManager(this,
                (FrameLayout) findViewById(R.id.deepseekWebView),
                (TextView) findViewById(R.id.tvLoginStatus),
                (TextView) findViewById(R.id.tvDeepSeekStatus),
                (TextView) findViewById(R.id.tvMcpStatus),
                findViewById(R.id.btnNewChat),
                findViewById(R.id.btnRefreshDS));

        // 初始化文件目录
        initFileDir();

        // 设置按钮点击事件
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });

        btnDeepSeek.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDeepSeek();
            }
        });

        // DeepSeek WebView 返回按钮
        findViewById(R.id.btnDeepSeekBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDeepSeek();
            }
        });

        // DeepSeek 的新会话和刷新按钮
        findViewById(R.id.btnNewChat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deepSeekPanel != null) deepSeekPanel.newChat();
            }
        });
        findViewById(R.id.btnRefreshDS).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deepSeekPanel != null) { openDeepSeek(); }
            }
        });

        // 点击监听地址复制到剪贴板
        tvAddress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String addr = tvAddress.getText().toString();
                if (addr == null || addr.isEmpty() || addr.equals("--")) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("MCP 地址", addr));
                Toast.makeText(MainActivity.this, "地址已复制", Toast.LENGTH_SHORT).show();
            }
        });

        // 技能安装目录
        final TextView tvSkillsPath = (TextView) findViewById(R.id.tvSkillsPath);
        final String skillsPath = com.example.agenttoolbox.skills.SkillManager.getInstance().getRuntimeSkillsPath();
        if (skillsPath != null) {
            tvSkillsPath.setText("技能目录: " + skillsPath);
            tvSkillsPath.setVisibility(View.VISIBLE);
            tvSkillsPath.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("技能目录", skillsPath));
                    Toast.makeText(MainActivity.this, "技能目录路径已复制", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 申请存储权限
        checkAndRequestPermissions();

        appendLog("Agent工具箱 MCP服务端已就绪");
        appendLog("点击\"启动MCP服务\"按钮开始服务");
    }
    
    /**
     * 初始化文件目录
     */
    private void initFileDir() {
        File filesDir = getFilesDir();
        if (!filesDir.exists()) {
            filesDir.mkdirs();
        }
        // 同时创建应用专属外部存储目录（不需要权限即可访问）
        try {
            File externalDir = getExternalFilesDir(null);
            if (externalDir != null && !externalDir.exists()) {
                externalDir.mkdirs();
            }
        } catch (Exception e) {
            // 忽略外部存储不可用的情况
        }
    }

    /**
     * 检查并申请存储权限
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：需要 MANAGE_EXTERNAL_STORAGE 权限
            if (Environment.isExternalStorageManager()) {
                appendLog("存储权限：已授权（所有文件访问）");
            } else {
                appendLog("正在请求存储权限...");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                } catch (Exception e) {
                    // 如果上面的 Intent 不可用，回退到通用的设置页
                    try {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                    } catch (Exception e2) {
                        appendLog("无法打开权限设置页，请手动授予权限");
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 - 10：需要运行时申请读写权限
            boolean hasRead = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean hasWrite = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (hasRead && hasWrite) {
                appendLog("存储权限：已授权");
            } else {
                appendLog("正在请求存储权限...");
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
            }
        } else {
            // Android 5 及以下：安装时自动获得权限
            appendLog("存储权限：已授权（低版本系统）");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                appendLog("存储权限：已授权");
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                appendLog("存储权限：被拒绝，外部文件工具可能受限");
                Toast.makeText(this, "未获得存储权限，部分功能受限", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 1003) {
            // 通知权限结果，继续启动服务
            appendLog("通知权限请求完成，继续启动服务");
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("通知权限已授予");
            } else {
                appendLog("通知权限被拒绝，但继续启动服务");
            }
            // 延迟一点再启动，让权限对话框完全关闭
            final Handler h2 = handler;
            h2.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServer();
                }
            }, 500);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    appendLog("存储权限：已授权（所有文件访问）");
                    Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("存储权限：未授予，外部文件工具可能受限");
                    Toast.makeText(this, "未获得完整存储权限，部分功能受限", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    /**
     * 启动服务 - 直接在Activity中启动
     */
    private void startServer() {
        try {
            appendLog("正在启动MCP服务...");

            // 先启动前台服务（WakeLock + 通知栏保活）
            Intent serviceIntent = new Intent(this, McpForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            mcpServer = new McpServer(PORT, MainActivity.this);
            // 初始化统一日志门面（同时输出到 UI 和 logcat）
            final Handler h = handler;
            AppLogger.init(new AppLogger.OnLogListener() {
                @Override
                public void onLog(final String message) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(message);
                        }
                    });
                }
            });
            mcpServer.setOnLogListener(new McpServer.OnLogListener() {
                @Override
                public void onLog(final String message) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(message);
                        }
                    });
                }
            });
            mcpServer.start();

            tvStatus.setText("运行中");
            tvStatus.setTextColor(getResources().getColor(R.color.success));
            statusChip.setText("运行中");
            statusChip.setBackgroundResource(R.drawable.chip_on);
            statusChip.setTextColor(getResources().getColor(R.color.success));
            tvAddress.setText("http://" + mcpServer.getLocalIpAddress() + ":" + PORT);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            appendLog("MCP服务启动成功");

        } catch (Exception e) {
            String error = "启动服务异常: " + e.getClass().getName() + "\n" + e.getMessage() + "\n\n堆栈:\n" + android.util.Log.getStackTraceString(e);
            appendLog(error);
            copyToClipboard(error);
        }
    }

    /**
     * 停止服务
     */
    private void stopServer() {
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }

        tvStatus.setText("已停止");
        tvStatus.setTextColor(getResources().getColor(R.color.text_muted));
        statusChip.setText("未启动");
        statusChip.setBackgroundResource(R.drawable.chip_off);
        statusChip.setTextColor(getResources().getColor(R.color.text_muted));
        tvAddress.setText("--");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("MCP Error", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 打开 DeepSeek 助手页面
     */
    private void openDeepSeek() {
        if (mcpServer == null || !mcpServer.isRunning()) {
            appendLog("提示：请先启动 MCP 服务，以便 DeepSeek 使用工具能力");
        }
        if (deepSeekPanel != null) {
            deepSeekPanel.open();
            deepSeekPanel.startMessageMonitor();
            mainPanel.setVisibility(View.GONE);
            deepseekContainer.setVisibility(View.VISIBLE);
        }
    }

    private void closeDeepSeek() {
        deepseekContainer.setVisibility(View.GONE);
        mainPanel.setVisibility(View.VISIBLE);
        if (deepSeekPanel != null) deepSeekPanel.close();
    }
    
    /**
     * 添加日志（新日志显示在最上面）
     */
    private void appendLog(String message) {
        String newLog = "[" + getCurrentTime() + "] " + message;
        // 将新日志插入到队列前面，使最新的日志显示在最上面
        logDeque.addFirst(newLog);
        
        // 限制日志条数，防止内存溢出
        if (logDeque.size() > MAX_LOGS) {
            logDeque.removeLast();
        }
        
        // 构建显示文本 - 每次都重建是可接受的，因为日志条数有限
        // 对于MAX_LOGS=1000，整个日志文本也只有~100KB，性能影响最小
        StringBuilder displayText = new StringBuilder();
        for (String log : logDeque) {
            displayText.append(log).append("\n");
        }
        tvLog.setText(displayText.toString());
        
        // 自动滚动到顶部
        final ScrollView scrollView = (ScrollView) tvLog.getParent();
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.scrollTo(0, 0);
            }
        });
    }
    
    /**
     * 获取当前时间字符串
     */
    private String getCurrentTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
    
}
