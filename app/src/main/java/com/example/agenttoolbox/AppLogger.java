package com.example.agenttoolbox;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志门面 — 同时输出到 UI (OnLogListener) 和 logcat
 * <p>
 * 日志格式: [HH:mm:ss.SSS] [LEVEL] [TAG] 消息
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
     */
    public static synchronized void init(OnLogListener listener) {
        AppLogger logger = getInstance();
        logger.logListener = listener;
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
    }
}
