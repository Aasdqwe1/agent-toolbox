package com.example.agenttoolbox;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

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
