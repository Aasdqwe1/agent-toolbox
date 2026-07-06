package com.example.agenttoolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import com.example.agenttoolbox.mcp.McpServer;
import java.io.IOException;

/**
 * MCP前台服务 - 保持应用在后台活跃运行
 * 使用WakeLock防止CPU休眠，前台通知确保服务不被系统杀死
 * 使用dataSync前台服务类型
 */
public class McpForegroundService extends Service {

    private static final String TAG = "McpForegroundService";
    private static final String CHANNEL_ID = "McpServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    private McpServer mcpServer;
    private PowerManager.WakeLock wakeLock;

    private static McpForegroundService instance;

    public static McpForegroundService getInstance() {
        return instance;
    }

    public McpServer getMcpServer() {
        return mcpServer;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "onCreate");

        // 创建前台通知渠道
        createNotificationChannel();

        // 获取WakeLock防止CPU休眠
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AgentToolbox:McpWakeLock"
            );
            wakeLock.setReferenceCounted(false);
        }

        // 启动前台服务 - 必须尽快调用
        startForeground(NOTIFICATION_ID, createNotification());
        Log.i(TAG, "startForeground called");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand, intent=" + intent + ", flags=" + flags + ", startId=" + startId);

        try {
            // 重新获取WakeLock
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 60 * 1000L); // 10小时
                Log.i(TAG, "WakeLock acquired");
            }

            // 启动MCP服务器
            if (mcpServer == null) {
                Log.i(TAG, "Creating McpServer");
                mcpServer = new McpServer(8080, this);
                Log.i(TAG, "McpServer created, calling start()");
                mcpServer.start();
                Log.i(TAG, "McpServer started successfully");
                Toast.makeText(this, "MCP服务启动成功", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            String error = "McpForegroundService异常: " + e.getClass().getName() + "\n" + e.getMessage() + "\n\n堆栈:\n" + android.util.Log.getStackTraceString(e);
            Log.e(TAG, error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }

        // START_STICKY确保服务被杀死后能重新启动
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 当任务被移除时，尝试重启服务
        Intent restartIntent = new Intent(this, McpForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MCP服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MCP服务运行中");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent工具箱")
            .setContentText("MCP服务运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    /**
     * 更新通知文本
     */
    public void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            Notification notification = createNotification();
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
}
