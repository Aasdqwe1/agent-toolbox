package com.example.agenttoolbox.tools;

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

    @Override
    public String getName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "读取指定路径的文本文件内容";
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
            
            JSONObject encoding = new JSONObject();
            encoding.put("type", "string");
            encoding.put("description", "编码格式，默认utf-8");
            encoding.put("default", "utf-8");
            properties.put("encoding", encoding);
            
            schema.put("properties", properties);
            
            String[] required = {"path"};
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
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";
        
        // 安全限制：防止路径遍历攻击
        if (path.contains("..") || path.startsWith("/")) {
            throw new Exception("不允许的路径格式，仅支持相对路径");
        }
        
        File file = new File(getBaseDir(), path);
        if (!file.exists()) {
            throw new Exception("文件不存在: " + path);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        
        return "文件内容:\n" + content.toString();
    }
    
    private File getBaseDir() {
        // 使用应用内部存储目录
        return new File("/data/data/com.example.agenttoolbox/files");
    }

}
