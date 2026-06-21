package com.example.agenttoolbox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.agenttoolbox.mcp.McpServer;

import java.io.File;

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
    
    private McpServer mcpServer;
    private Handler handler;
    private StringBuilder logBuilder = new StringBuilder();
    
    private static final int PORT = 8080;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());

        // 初始化视图
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvAddress = (TextView) findViewById(R.id.tvAddress);
        tvLog = (TextView) findViewById(R.id.tvLog);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnStop = (Button) findViewById(R.id.btnStop);
        btnDeepSeek = (Button) findViewById(R.id.btnDeepSeek);

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
     * 启动服务
     */
    private void startServer() {
        try {
            mcpServer = new McpServer(PORT, MainActivity.this);
            mcpServer.setOnLogListener(new McpServer.OnLogListener() {
                @Override
                public void onLog(final String message) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(message);
                        }
                    });
                }
            });
            mcpServer.start();
            
            tvStatus.setText("服务状态：运行中");
            tvAddress.setText("监听地址：http://" + mcpServer.getLocalIpAddress() + ":" + PORT);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            
        } catch (Exception e) {
            appendLog("启动服务失败: " + e.getMessage());
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
        
        tvStatus.setText("服务状态：已停止");
        tvAddress.setText("监听地址：--");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }
    
    /**
     * 打开 DeepSeek 助手页面
     */
    private void openDeepSeek() {
        // 如果 MCP 服务没启动，先提示用户启动
        if (!McpServer.isServiceRunning()) {
            appendLog("提示：请先启动 MCP 服务，以便 DeepSeek 使用工具能力");
        }
        
        Intent intent = new Intent(MainActivity.this, DeepSeekActivity.class);
        startActivity(intent);
    }
    
    /**
     * 添加日志
     */
    private void appendLog(String message) {
        logBuilder.append("[").append(getCurrentTime()).append("] ")
            .append(message).append("\n");
        tvLog.setText(logBuilder.toString());
        
        // 自动滚动到底部
        final ScrollView scrollView = (ScrollView) tvLog.getParent();
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
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
