package com.example.agenttoolbox;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一日志门面 — 同时输出到 UI (OnLogListener)、logcat 和本地文件
 * <p>
 * 日志格式: [HH:mm:ss.SSS] [LEVEL] [TAG] 消息
 * <p>
 * 文件日志: &lt;filesDir&gt;/logs/mcp.log，超过 1MB 自动清空重写。
 * 文件 I/O 在后台线程执行，不阻塞调用线程。
 * 单条日志超过 8KB 自动截断，防止大消息撑爆内存。
 * <p>
 * 支持级别: DEBUG / INFO / WARN / ERROR
 * 支持敏感数据截断: logger.info("TAG", longMessage, 2000) 自动截断超长消息
 */
public class AppLogger {

    public interface OnLogListener {
        void onLog(String message);
    }

    private static AppLogger instance;
    private OnLogListener logListener;
    private boolean logcatEnabled = true;
    private int defaultMaxLen = 0; // 0 = 不限制
    private File logFile;
    private long fileWriteCount = 0;
    private static final long MAX_LOG_FILE_SIZE = 1 * 1024 * 1024; // 1MB
    private static final int MAX_MSG_LENGTH = 8 * 1024; // 单条日志最大 8KB
    private static final int FILE_SIZE_CHECK_INTERVAL = 50; // 每 50 次写检查一次文件大小

    /** 后台文件写入线程池（单线程，防堆积） */
    private final ExecutorService fileWriter =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AppLogger-File");
            t.setDaemon(true);
            return t;
        });

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // 日志级别常量
    public static final int LEVEL_DEBUG = 0;
    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private static final String[] LEVEL_TAGS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int[] ANDROID_LOG_LEVELS = {
            Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR
    };

    private AppLogger() {
    }

    public static synchronized AppLogger getInstance() {
        if (instance == null) {
            instance = new AppLogger();
        }
        return instance;
    }

    /**
     * 初始化（由 MainActivity 在创建 McpServer 前调用）
     * @param listener UI 日志回调
     * @param context 用于定位文件存储目录
     */
    public static synchronized void init(OnLogListener listener, Context context) {
        AppLogger logger = getInstance();
        logger.logListener = listener;
        // 初始化文件日志：<filesDir>/logs/mcp.log
        if (context != null) {
            File logsDir = new File(context.getFilesDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            logger.logFile = new File(logsDir, "mcp.log");
        }
    }

    /**
     * 设置是否同时输出到 logcat（默认 true）
     */
    public void setLogcatEnabled(boolean enabled) {
        this.logcatEnabled = enabled;
    }

    /**
     * 设置默认截断长度（0 = 不限制）
     */
    public void setDefaultMaxLen(int maxLen) {
        this.defaultMaxLen = maxLen;
    }

    // ========== 便捷静态方法 ==========

    public static void d(String tag, String msg) {
        getInstance().logInternal(LEVEL_DEBUG, tag, msg, null, 0);
    }

    public static void d(String tag, String msg, Throwable tr) {
        getInstance().logInternal(LEVEL_DEBUG, tag, msg, tr, 0);
    }

    public static void i(String tag, String msg) {
        getInstance().logInternal(LEVEL_INFO, tag, msg, null, 0);
    }

    public static void w(String tag, String msg) {
        getInstance().logInternal(LEVEL_WARN, tag, msg, null, 0);
    }

    public static void e(String tag, String msg) {
        getInstance().logInternal(LEVEL_ERROR, tag, msg, null, 0);
    }

    public static void e(String tag, String msg, Throwable tr) {
        getInstance().logInternal(LEVEL_ERROR, tag, msg, tr, 0);
    }

    /** 带截断的 info 日志 */
    public static void i(String tag, String msg, int maxLen) {
        getInstance().logInternal(LEVEL_INFO, tag, msg, null, maxLen);
    }

    /** 带截断的 debug 日志 */
    public static void d(String tag, String msg, int maxLen) {
        getInstance().logInternal(LEVEL_DEBUG, tag, msg, null, maxLen);
    }

    /** 带截断的 error 日志 */
    public static void e(String tag, String msg, int maxLen) {
        getInstance().logInternal(LEVEL_ERROR, tag, msg, null, maxLen);
    }

    // ========== 内部实现 ==========

    private void logInternal(int level, String tag, String msg, Throwable tr, int maxLen) {
        // 截断
        String safeMsg = msg;
        int actualMaxLen = maxLen > 0 ? maxLen : defaultMaxLen;
        if (actualMaxLen > 0 && safeMsg != null && safeMsg.length() > actualMaxLen) {
            safeMsg = safeMsg.substring(0, actualMaxLen) + " ...[截断 " + (safeMsg.length() - actualMaxLen) + " 字符]";
        }
        // 硬限制：单条日志不超过 8KB（防止 JSBridge 等传入 500KB+ 消息撑爆内存）
        if (safeMsg != null && safeMsg.length() > MAX_MSG_LENGTH) {
            safeMsg = safeMsg.substring(0, MAX_MSG_LENGTH) + " ...[截断 " + (safeMsg.length() - MAX_MSG_LENGTH) + " 字符]";
        }

        String timestamp = sdf.format(new Date());
        String levelTag = (level >= 0 && level < LEVEL_TAGS.length) ? LEVEL_TAGS[level] : "????";

        // 格式化带时间戳和级别的日志
        String formattedMsg = "[" + timestamp + "] [" + levelTag + "] [" + tag + "] " + (safeMsg != null ? safeMsg : "");

        // 输出到 UI
        if (logListener != null) {
            logListener.onLog(formattedMsg);
        }

        // 输出到 logcat
        if (logcatEnabled) {
            int androidLevel = (level >= 0 && level < ANDROID_LOG_LEVELS.length) ? ANDROID_LOG_LEVELS[level] : Log.DEBUG;
            if (tr != null) {
                Log.println(androidLevel, tag, safeMsg + "\n" + Log.getStackTraceString(tr));
            } else {
                Log.println(androidLevel, tag, safeMsg != null ? safeMsg : "");
            }
        }

        // 异步输出到本地文件，不阻塞调用线程
        final String finalMsg = formattedMsg;
        fileWriter.submit(() -> writeToFile(finalMsg));
    }

    /**
     * 写入日志到本地文件，超过 1MB 自动清空重写。
     * 仅在 fileWriter 后台线程中调用。
     * 每 50 次写检查一次文件大小，避免频繁 stat 系统调用。
     */
    private void writeToFile(String message) {
        if (logFile == null) return;
        try {
            fileWriteCount++;
            // 每 FILE_SIZE_CHECK_INTERVAL 次检查一次文件大小，减少 stat 系统调用
            if (fileWriteCount % FILE_SIZE_CHECK_INTERVAL == 0) {
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                    logFile.delete();
                    logFile.createNewFile();
                }
            }
            FileOutputStream fos = new FileOutputStream(logFile, true);
            try {
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                writer.write(message);
                writer.write('\n');
                writer.close();
            } finally {
                fos.close();
            }
        } catch (Exception ignored) {
            // 文件日志失败不应影响主流程
        }
    }
}
