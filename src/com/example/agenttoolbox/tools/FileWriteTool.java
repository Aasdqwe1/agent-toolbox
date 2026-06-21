package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 文件写入工具
 * 支持：1) 内部存储相对路径；2) 外部存储路径（Download 等公共目录）
 */
public class FileWriteTool implements Tool {

    // 允许的外部存储目录名称
    private static final String[] ALLOWED_DIRS = {
            "Download", "Documents", "Pictures", "DCIM", "Movies"
    };

    // 允许写入的外部存储路径白名单（完整路径）
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

    @Override
    public String getName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "向指定路径写入文本内容。支持：1) 相对路径（基于应用内部存储）；2) /storage/emulated/0/Download/xxx.txt（外部公共存储）；3) /Download/xxx.txt（外部下载目录简写）";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件路径：1) 相对路径（内部存储）；2) /storage/emulated/0/Download/...（外部存储）；3) /Download/...（外部下载目录快捷方式）");
            properties.put("path", path);
            
            JSONObject content = new JSONObject();
            content.put("type", "string");
            content.put("description", "待写入的文本内容");
            properties.put("content", content);
            
            JSONObject append = new JSONObject();
            append.put("type", "boolean");
            append.put("description", "是否追加模式，默认false覆盖");
            append.put("default", false);
            properties.put("append", append);
            
            schema.put("properties", properties);
            
            String[] required = {"path", "content"};
            JSONArray requiredArray = new JSONArray();
            for (String r : required) requiredArray.put(r);
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String path = arguments.getString("path");
        String content = arguments.getString("content");
        boolean append = arguments.has("append") && arguments.getBoolean("append");
        
        // 安全限制：防止路径遍历攻击
        if (path.contains("..")) {
            throw new Exception("不允许的路径格式，禁止使用 '..'");
        }
        
        File file;
        
        // 判断是外部存储路径还是内部存储路径
        if (isExternalStoragePath(path)) {
            // 外部存储路径，需要路径白名单验证
            if (!isAllowedExternalPath(path)) {
                throw new Exception("不允许的外部存储路径，仅支持 Download/Documents/Pictures/DCIM/Movies 等公共目录");
            }
            
            // 已验证为允许的外部存储路径
            file = new File(path);
        } else if (path.startsWith("/")) {
            // 其他绝对路径被拒绝
            throw new Exception("不允许的路径格式，仅支持相对路径或 /storage/emulated/0/Download 等完整外部存储路径");
        } else {
            // 相对路径，使用内部存储
            file = new File(getBaseDir(), path);
        }
        
        // 创建父目录
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new Exception("无法创建目录: " + parentDir.getAbsolutePath());
            }
        }
        
        // 写入文件
        OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(file, append), "UTF-8");
        writer.write(content);
        writer.close();
        
        return "文件写入成功: " + file.getAbsolutePath() + " (" + content.length() + " 字符)";
    }
    
    /**
     * 检查是否是外部存储路径
     */
    private boolean isExternalStoragePath(String path) {
        // 只允许 /storage/emulated/0/... 或 /sdcard/... 路径
        return path.startsWith("/storage/emulated/0/") || path.startsWith("/sdcard/");
    }
    
    /**
     * 检查外部存储路径是否在白名单中
     */
    private boolean isAllowedExternalPath(String path) {
        for (String prefix : ALLOWED_EXTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    
    private File getBaseDir() {
        // 使用应用内部存储目录
        return new File("/data/data/com.example.agenttoolbox/files");
    }

}
