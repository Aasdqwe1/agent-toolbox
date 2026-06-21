package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 文件列表工具 - 列出目录内容
 */
public class FileListTool implements Tool {

    @Override
    public String getName() {
        return "file_list";
    }

    @Override
    public String getDescription() {
        return "列出指定目录下的文件和子目录";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "目录路径，支持：1) 相对路径（内部存储）；2) /Download/、/Documents/、/Pictures/、/DCIM/、/Movies/ 等简写；3) /storage/emulated/0/... 完整外部路径；不填则列出内部存储");
            properties.put("path", path);
            
            schema.put("properties", properties);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        File dir;
        
        if (arguments.has("path")) {
            String path = arguments.getString("path");
            
            // 安全限制：防止路径遍历攻击
            if (path.contains("..")) {
                throw new Exception("不允许的路径格式，禁止使用 '..'");
            }
            
            // 解析路径类型
            if (path.startsWith("/storage/") || path.startsWith("/sdcard") 
                    || path.startsWith("/external")) {
                // 外部存储完整路径
                dir = new File(path);
            } else if (path.startsWith("/Download/") || path.startsWith("/Documents/") 
                    || path.startsWith("/Pictures/") || path.startsWith("/DCIM/") 
                    || path.startsWith("/Movies/")) {
                // 外部存储简写路径
                dir = new File(getExternalStorageDir(), path.substring(1));
            } else {
                // 内部存储相对路径
                dir = new File(getBaseDir(), path);
            }
        } else {
            // 默认列出内部存储根目录
            dir = getBaseDir();
        }
        
        if (!dir.exists()) {
            // 尝试应用专属外部存储目录
            File appExternal = arguments.has("path") 
                    ? new File(getAppExternalDir(), arguments.getString("path"))
                    : getAppExternalDir();
            if (appExternal.exists()) {
                dir = appExternal;
            } else {
                throw new Exception("目录不存在: " + dir.getAbsolutePath());
            }
        }
        
        if (!dir.isDirectory()) {
            throw new Exception("这不是一个目录: " + dir.getAbsolutePath());
        }
        
        if (!dir.canRead()) {
            throw new Exception("目录不可读，请在系统设置中授权存储权限: " + dir.getAbsolutePath());
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            throw new Exception("无法读取目录内容，请检查权限: " + dir.getAbsolutePath());
        }
        
        StringBuilder result = new StringBuilder();
        result.append("目录内容 (").append(dir.getAbsolutePath()).append("):\n");
        result.append("共 ").append(files.length).append(" 个项目\n\n");
        
        // 按名称排序（目录优先）
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                // 目录优先
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });
        
        for (File file : files) {
            String type = file.isDirectory() ? "[目录]" : "[文件]";
            String size = file.isDirectory() ? "" : formatFileSize(file.length());
            String line = String.format("%-10s %-12s %s", type, size, file.getName());
            result.append(line).append("\n");
        }
        
        return result.toString();
    }
    
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }
    
    private File getBaseDir() {
        // 应用内部存储目录
        return new File("/data/data/com.example.agenttoolbox/files");
    }
    
    private File getExternalStorageDir() {
        // 外部存储根目录
        return new File("/storage/emulated/0");
    }
    
    private File getAppExternalDir() {
        // 应用专属外部存储目录（不需要权限）
        return new File("/storage/emulated/0/Android/data/com.example.agenttoolbox/files");
    }

}
