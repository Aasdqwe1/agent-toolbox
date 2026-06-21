package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 文件读取工具
 */
public class FileReadTool implements Tool {
    
    // 允许的外部存储目录简写（用于路径转换）
    private static final String[] ALLOWED_SHORTHAND_DIRS = {
            "/Download/", "/Documents/", "/Pictures/", "/DCIM/", "/Movies/"
    };

    @Override
    public String getName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "读取指定路径的文本文件内容，支持内部存储路径或外部存储路径";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件路径，支持：1) 相对路径（内部存储）；2) /storage/emulated/0/...（外部存储，需授权）；3) /Download/、/Documents/ 等简写");
            properties.put("path", path);
            
            JSONObject encoding = new JSONObject();
            encoding.put("type", "string");
            encoding.put("description", "编码格式，默认utf-8");
            encoding.put("default", "utf-8");
            properties.put("encoding", encoding);
            
            schema.put("properties", properties);
            
            String[] required = {"path"};
            JSONArray requiredArray = new JSONArray();
            for (String r : required) requiredArray.put(r);
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            // 正常情况下不会发生
            e.printStackTrace();
        }
        
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String path = arguments.getString("path");
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";
        
        File file;
        
        // 安全限制：防止路径遍历攻击
        if (path.contains("..")) {
            throw new Exception("不允许的路径格式，禁止使用 '..'");
        }
        
        // 解析路径类型
        if (path.startsWith("/storage/") || path.startsWith("/sdcard") 
                || path.startsWith("/external")) {
            // 外部存储完整路径
            file = new File(path);
        } else if (isShorthandExternalPath(path)) {
            // 外部存储简写路径，转换为完整路径
            file = new File(getExternalStorageDir(), path.substring(1));
        } else {
            // 内部存储相对路径
            file = new File(getBaseDir(), path);
        }
        
        if (!file.exists()) {
            // 尝试应用专属外部存储目录
            File appExternal = new File(getAppExternalDir(), path);
            if (appExternal.exists()) {
                file = appExternal;
            } else {
                throw new Exception("文件不存在: " + file.getAbsolutePath());
            }
        }
        
        if (!file.canRead()) {
            throw new Exception("文件不可读，请检查权限，请在系统设置中授权存储权限");
        }
        
        if (file.isDirectory()) {
            throw new Exception("这是一个目录，不是文件，请使用 file_list 工具列出目录内容");
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        
        return "文件内容 (" + file.getAbsolutePath() + "):\n" + content.toString();
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
    
    /**
     * 检查是否是外部存储简写路径（/Download/...、/Documents/... 等）
     */
    private boolean isShorthandExternalPath(String path) {
        for (String shorthand : ALLOWED_SHORTHAND_DIRS) {
            if (path.startsWith(shorthand)) {
                return true;
            }
        }
        return false;
    }

}
