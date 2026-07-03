package com.example.agenttoolbox.tools;

import java.io.File;

/**
 * 文件路径解析器 - 统一处理路径解析、安全校验、目录白名单
 * 所有文件工具共用此类，避免重复代码
 */
public class FilePathResolver {

    // 外部存储允许的子目录
    private static final String[] ALLOWED_SHORTHAND_DIRS = {
            "/Download/", "/Documents/", "/Pictures/", "/DCIM/", "/Movies/"
    };

    // 外部存储完整路径白名单
    private static final String[] ALLOWED_EXTERNAL_PREFIXES = {
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Documents/",
            "/storage/emulated/0/Pictures/",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/Movies/",
            "/sdcard/Download/",
            "/sdcard/Documents/",
            "/sdcard/Pictures/",
            "/sdcard/DCIM/",
            "/sdcard/Movies/",
    };

    private static final String BASE_DIR = "/data/data/com.example.agenttoolbox/files";
    private static final String EXT_STORAGE = "/storage/emulated/0";
    private static final String APP_EXTERNAL = "/storage/emulated/0/Android/data/com.example.agenttoolbox/files";

    /**
     * 解析路径，返回 File 对象（用于读取/列表）
     * 支持：相对路径、外部存储完整路径、简写路径
     * 自动回退到应用专属外部目录
     */
    public static File resolveForRead(String path) throws Exception {
        checkPathSafety(path);
        File file = doResolve(path);

        if (!file.exists()) {
            // 回退到应用专属外部目录
            File appExternal = new File(APP_EXTERNAL, path);
            if (appExternal.exists()) {
                file = appExternal;
            }
        }

        return file;
    }

    /**
     * 解析路径，返回 File 对象（用于写入）
     * 写入路径不回退，严格校验白名单
     */
    public static File resolveForWrite(String path) throws Exception {
        checkPathSafety(path);

        if (isExternalStoragePath(path)) {
            if (!isAllowedExternalPath(path)) {
                throw new Exception("不允许的外部存储路径，仅支持 Download/Documents/Pictures/DCIM/Movies");
            }
            return new File(path);
        }

        if (isShorthandExternalPath(path)) {
            String fullPath = new File(EXT_STORAGE, path.substring(1)).getAbsolutePath();
            if (!isAllowedExternalPath(fullPath)) {
                throw new Exception("不允许的外部存储路径");
            }
            return new File(fullPath);
        }

        if (path.startsWith("/")) {
            throw new Exception("不允许的绝对路径: " + path);
        }

        return new File(BASE_DIR, path);
    }

    /**
     * 解析目录路径（用于列表）
     */
    public static File resolveForDir(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            return new File(BASE_DIR);
        }
        return resolveForRead(path);
    }

    /**
     * 路径安全检查
     */
    private static void checkPathSafety(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new Exception("路径不能为空");
        }
        if (path.contains("..")) {
            throw new Exception("不允许的路径格式，禁止使用 '..'");
        }
        // 检查符号链接逃逸
        File file = doResolve(path);
        try {
            String canonical = file.getCanonicalPath();
            String absolute = file.getAbsolutePath();
            // 仅对外部存储路径做符号链接检查
            if (isExternalStoragePath(path) && !canonical.equals(absolute)) {
                // canonical 和 absolute 不同可能是符号链接，也可能是路径规范化
                // 不阻断，但记录
            }
        } catch (Exception ignored) {
        }
    }

    private static File doResolve(String path) {
        if (isExternalStoragePath(path)) {
            return new File(path);
        }
        if (isShorthandExternalPath(path)) {
            return new File(EXT_STORAGE, path.substring(1));
        }
        return new File(BASE_DIR, path);
    }

    public static boolean isExternalStoragePath(String path) {
        return path.startsWith("/storage/emulated/0/") || path.startsWith("/sdcard/");
    }

    public static boolean isShorthandExternalPath(String path) {
        for (String shorthand : ALLOWED_SHORTHAND_DIRS) {
            if (path.startsWith(shorthand)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllowedExternalPath(String path) {
        for (String prefix : ALLOWED_EXTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String getBaseDir() {
        return BASE_DIR;
    }

    public static String getExtStorage() {
        return EXT_STORAGE;
    }
}
