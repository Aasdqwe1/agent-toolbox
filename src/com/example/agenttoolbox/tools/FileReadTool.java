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
        return "读取指定路径的文本文件内容，支持按行号范围读取和显示行号。路径支持：1) 相对路径（内部存储）；2) /storage/emulated/0/...（外部存储）；3) /Download/、/Documents/ 等简写";
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

            JSONObject line = new JSONObject();
            line.put("type", "integer");
            line.put("description", "起始行号（从1开始），可选。设置后只读取从该行开始的内容");
            properties.put("line", line);

            JSONObject endLine = new JSONObject();
            endLine.put("type", "integer");
            endLine.put("description", "结束行号（包含），可选。不填则读到文件末尾");
            properties.put("end_line", endLine);

            JSONObject showLineNumbers = new JSONObject();
            showLineNumbers.put("type", "boolean");
            showLineNumbers.put("description", "是否在每行前显示行号，默认false");
            showLineNumbers.put("default", false);
            properties.put("show_line_numbers", showLineNumbers);
            
            schema.put("properties", properties);
            
            String[] required = {"path"};
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
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";
        int startLine = arguments.has("line") ? arguments.getInt("line") : -1;
        int endLine = arguments.has("end_line") ? arguments.getInt("end_line") : -1;
        boolean showLineNumbers = arguments.has("show_line_numbers") && arguments.getBoolean("show_line_numbers");
        
        File file;
        
        if (path.contains("..")) {
            throw new Exception("不允许的路径格式，禁止使用 '..'");
        }
        
        if (path.startsWith("/storage/") || path.startsWith("/sdcard") 
                || path.startsWith("/external")) {
            file = new File(path);
        } else if (isShorthandExternalPath(path)) {
            file = new File(getExternalStorageDir(), path.substring(1));
        } else {
            file = new File(getBaseDir(), path);
        }
        
        if (!file.exists()) {
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

        if (startLine < 1 && startLine != -1) {
            throw new Exception("起始行号必须大于等于 1");
        }
        if (endLine < 1 && endLine != -1) {
            throw new Exception("结束行号必须大于等于 1");
        }
        if (startLine > 0 && endLine > 0 && endLine < startLine) {
            throw new Exception("结束行号不能小于起始行号");
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
        StringBuilder content = new StringBuilder();
        String line;
        int lineNum = 0;
        int displayedLines = 0;
        while ((line = reader.readLine()) != null) {
            lineNum++;
            if (startLine > 0 && lineNum < startLine) {
                continue;
            }
            if (endLine > 0 && lineNum > endLine) {
                break;
            }
            if (showLineNumbers) {
                content.append(String.format("%4d  ", lineNum));
            }
            content.append(line).append("\n");
            displayedLines++;
        }
        reader.close();

        String rangeInfo;
        if (startLine > 0 || endLine > 0) {
            rangeInfo = "第 " + (startLine > 0 ? startLine : 1) + "-" + (endLine > 0 ? endLine : lineNum) + " 行（共 " + displayedLines + " 行，全文 " + lineNum + " 行）";
        } else {
            rangeInfo = "共 " + lineNum + " 行";
        }
        
        return "文件内容 (" + file.getAbsolutePath() + ") " + rangeInfo + ":\n" + content.toString();
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
