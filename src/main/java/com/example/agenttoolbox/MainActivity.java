package com.example.agenttoolbox;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
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
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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
    private Button btnCheckPort;
    private EditText etPort;
    private Spinner spinnerBind;
    private TextView statusChip;

    
    private McpServer mcpServer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler uiLogHandler = new Handler(Looper.getMainLooper());
    private Deque<String> logDeque = new LinkedList<>();
    private int logTotalChars = 0; // 当前 deque 中总字符数
    private static final int MAX_LOGS = 500;       // 最多保存 500 条（原 1000）
    private static final int MAX_LOG_CHARS = 200 * 1024;         // 总字符上限 200KB
    private static final int MAX_DISPLAY_MSG_LEN = 2 * 1024;     // 单条显示截断 2KB
    
    private static final int DEFAULT_PORT = 8080;
    private int currentPort = DEFAULT_PORT;
    private String currentBindAddress = "0.0.0.0";
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
        etPort = (EditText) findViewById(R.id.etPort);
        spinnerBind = (Spinner) findViewById(R.id.spinnerBind);
        btnCheckPort = (Button) findViewById(R.id.btnCheckPort);

        // 绑定地址下拉选项
        String[] bindOptions = {"0.0.0.0 (所有网卡)", "127.0.0.1 (仅本机)"};
        ArrayAdapter<String> bindAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, bindOptions);
        bindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBind.setAdapter(bindAdapter);

        // 读取上次保存的端口和绑定地址
        SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
        int savedPort = prefs.getInt("port", DEFAULT_PORT);
        String savedBind = prefs.getString("bind_address", "0.0.0.0");
        etPort.setText(String.valueOf(savedPort));
        spinnerBind.setSelection("127.0.0.1".equals(savedBind) ? 1 : 0);

        // 端口检查按钮
        btnCheckPort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPort();
            }
        });

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
        com.example.agenttoolbox.skills.SkillManager.getInstance().init(this);
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

        // 检查 APP 更新（异步，不影响启动）
        UpdateChecker.check(this);
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

            // 如果已有服务器在运行，先停掉（防止 EADDRINUSE）
            if (mcpServer != null && mcpServer.isRunning()) {
                mcpServer.stop();
                mcpServer = null;
            }

            // 读取用户输入的端口和绑定地址
            try {
                currentPort = Integer.parseInt(etPort.getText().toString().trim());
            } catch (NumberFormatException e) {
                currentPort = DEFAULT_PORT;
                appendLog("端口格式错误，使用默认端口 " + DEFAULT_PORT);
            }
            currentBindAddress = spinnerBind.getSelectedItemPosition() == 0 ? "0.0.0.0" : "127.0.0.1";

            // 先检查端口可用性
            String portError = McpServer.checkPortAvailable(currentPort, currentBindAddress);
            if (portError != null) {
                appendLog("❌ " + portError);
                appendLog("请更换端口后重试");
                return;
            }

            // 保存配置到 SharedPreferences
            SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
            prefs.edit()
                .putInt("port", currentPort)
                .putString("bind_address", currentBindAddress)
                .apply();

            mcpServer = new McpServer(currentPort, currentBindAddress, MainActivity.this);
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
            }, MainActivity.this);
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
            String displayAddr = currentBindAddress.equals("127.0.0.1")
                ? "127.0.0.1" : mcpServer.getLocalIpAddress();
            tvAddress.setText("http://" + displayAddr + ":" + currentPort);
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
     * 检查端口可用性
     */
    private void checkPort() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            appendLog("❌ 端口格式错误: " + etPort.getText());
            return;
        }
        String bindAddr = spinnerBind.getSelectedItemPosition() == 0 ? "0.0.0.0" : "127.0.0.1";
        String result = McpServer.checkPortAvailable(port, bindAddr);
        if (result == null) {
            appendLog("✅ 端口 " + port + " 可用 (绑定: " + bindAddr + ")");
        } else {
            appendLog("❌ " + result);
        }
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
        // 如果 MCP 服务没启动，先提示用户启动
        if (mcpServer == null || !mcpServer.isRunning()) {
            appendLog("提示：请先启动 MCP 服务，以便 DeepSeek 使用工具能力");
        }

        Intent intent = new Intent(MainActivity.this, DeepSeekActivity.class);
        startActivity(intent);
    }
    
    /**
     * 添加日志（新日志显示在最上面）
     * 内存安全：截断长消息、限制总字符数、批量更新 UI。
     */
    private void appendLog(String message) {
        // 截断超长消息（防止 JSBridge 传入 500KB+ JSON 撑爆内存）
        String displayMsg = message;
        if (displayMsg != null && displayMsg.length() > MAX_DISPLAY_MSG_LEN) {
            displayMsg = displayMsg.substring(0, MAX_DISPLAY_MSG_LEN)
                + "...[截断 " + (displayMsg.length() - MAX_DISPLAY_MSG_LEN) + " 字符]";
        }
        String newLog = "[" + getCurrentTime() + "] " + (displayMsg != null ? displayMsg : "");
        int newLen = newLog.length();

        // 将新日志插入到队列前面
        logDeque.addFirst(newLog);
        logTotalChars += newLen;

        // 限制总字符数（优先于条数限制）：从尾部移除直到低于上限
        while (logTotalChars > MAX_LOG_CHARS && logDeque.size() > 1) {
            String removed = logDeque.removeLast();
            logTotalChars -= removed.length();
        }
        // 兜底条数限制
        while (logDeque.size() > MAX_LOGS) {
            String removed = logDeque.removeLast();
            logTotalChars -= removed.length();
        }

        // 使用 Handler 节流 UI 更新：200ms 内的多次 appendLog 只刷新一次
        uiLogHandler.removeCallbacks(uiRefreshRunnable);
        uiLogHandler.postDelayed(uiRefreshRunnable, 200);
    }

    /** 批量刷新 UI 日志显示 */
    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            StringBuilder displayText = new StringBuilder(logTotalChars + logDeque.size());
            for (String log : logDeque) {
                displayText.append(log).append('\n');
            }
            tvLog.setText(displayText.toString());
            // 自动滚动到顶部
            ScrollView scrollView = (ScrollView) tvLog.getParent();
            scrollView.scrollTo(0, 0);
        }
    };
    
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
