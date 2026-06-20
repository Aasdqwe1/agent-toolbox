package com.example.agenttoolbox.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 文件写入工具
 */
public class FileWriteTool implements Tool {

    @Override
    public String getName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "向指定路径写入文本内容";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            
            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件相对路径（基于应用内部存储）");
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
            schema.put("required", required);
        } catch (JSONException e) {
            // 正常情况下不会发生
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
        if (path.contains("..") || path.startsWith("/")) {
            throw new Exception("不允许的路径格式，仅支持相对路径");
        }
        
        File file = new File(getBaseDir(), path);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(file, append), "UTF-8");
        writer.write(content);
        writer.close();
        
        return "文件写入成功: " + path + " (" + content.length() + " 字符)";
    }
    
    private File getBaseDir() {
        // 使用应用内部存储目录
        return new File("/data/data/com.example.agenttoolbox/files");
    }

}
