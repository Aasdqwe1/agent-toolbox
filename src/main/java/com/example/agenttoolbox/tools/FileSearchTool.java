package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件搜索工具 —— 按文件名和文件内容搜索，不限于 txt，支持多关键词。
 */
public class FileSearchTool implements Tool {

    @Override
    public String getName() {
        return "file_search";
    }

    @Override
    public String getDescription() {
        return "搜索文件名和文件内容。参数 path(搜索路径，默认 /sdcard)、keywords(关键词数组，必填，多个关键词取交集)、max_results(最大结果数，默认30)、search_content(是否搜索文件内容，默认false)";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "搜索起始路径，默认 /sdcard");
            path.put("default", "/sdcard");
            properties.put("path", path);

            JSONObject keywords = new JSONObject();
            keywords.put("type", "array");
            keywords.put("description", "关键词数组（必填），多个关键词时取交集（结果必须包含所有关键词）");
            JSONObject ki = new JSONObject();
            ki.put("type", "string");
            keywords.put("items", ki);
            properties.put("keywords", keywords);

            JSONObject maxResults = new JSONObject();
            maxResults.put("type", "integer");
            maxResults.put("description", "最大返回结果数，默认 30");
            maxResults.put("default", 30);
            properties.put("max_results", maxResults);

            JSONObject searchContent = new JSONObject();
            searchContent.put("type", "boolean");
            searchContent.put("description", "是否搜索文件内容（默认只搜索文件名），搜索内容会较慢");
            searchContent.put("default", false);
            properties.put("search_content", searchContent);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("keywords");
            schema.put("required", required);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String basePath = arguments.optString("path", "/sdcard");
        int maxResults = arguments.optInt("max_results", 30);
        boolean searchContent = arguments.optBoolean("search_content", false);

        JSONArray kwArr = arguments.optJSONArray("keywords");
        if (kwArr == null || kwArr.length() == 0) {
            throw new Exception("keywords 不能为空");
        }
        List<String> keywords = new ArrayList<>();
        for (int i = 0; i < kwArr.length(); i++) {
            String kw = kwArr.optString(i, "").trim().toLowerCase();
            if (!kw.isEmpty()) keywords.add(kw);
        }
        if (keywords.isEmpty()) throw new Exception("keywords 不能为空");

        File root = new File(basePath);
        if (!root.exists()) throw new Exception("路径不存在: " + basePath);
        if (!root.isDirectory()) throw new Exception("路径不是目录: " + basePath);

        List<String> results = new ArrayList<>();
        searchFile(root, keywords, searchContent, maxResults, results);

        if (results.isEmpty()) {
            return "未找到匹配的文件（关键词: " + String.join(", ", keywords) + "）\n搜索路径: " + basePath + "\n搜索内容: " + (searchContent ? "是" : "否（仅文件名）");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个文件（关键词: ").append(String.join(", ", keywords) + "）\n");
        sb.append("搜索路径: ").append(basePath).append("\n");
        if (searchContent) sb.append("搜索内容: 是\n");
        sb.append("\n");
        for (String r : results) {
            sb.append(r).append("\n");
        }
        if (results.size() >= maxResults) {
            sb.append("\n（已达到最大结果数 ").append(maxResults).append("，可能还有更多匹配）");
        }
        return sb.toString();
    }

    private void searchFile(File dir, List<String> keywords, boolean searchContent, int maxResults, List<String> results) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (results.size() >= maxResults) return;

            try {
                String name = f.getName().toLowerCase();
                boolean nameMatch = true;
                for (String kw : keywords) {
                    if (!name.contains(kw)) { nameMatch = false; break; }
                }

                if (nameMatch) {
                    results.add((f.isDirectory() ? "[目录] " : "[文件] ") + f.getAbsolutePath() + (f.isFile() ? " (" + formatSize(f.length()) + ")" : ""));
                    if (results.size() >= maxResults) return;
                }

                if (f.isDirectory()) {
                    // 跳过隐藏目录和特殊目录
                    if (!f.getName().startsWith(".")) {
                        searchFile(f, keywords, searchContent, maxResults, results);
                    }
                } else if (searchContent && !nameMatch) {
                    // 搜索文件内容（仅在文件名不匹配时）
                    try {
                        String content = readFileHead(f, 4096);
                        if (content != null) {
                            boolean contentMatch = true;
                            for (String kw : keywords) {
                                if (!content.contains(kw)) { contentMatch = false; break; }
                            }
                            if (contentMatch) {
                                results.add("[内容匹配] " + f.getAbsolutePath() + " (" + formatSize(f.length()) + ")");
                            }
                        }
                    } catch (Exception ignored) {
                        // 跳过无法读取的文件（二进制等）
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private String readFileHead(File f, int maxBytes) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            char[] buf = new char[Math.min(maxBytes, 8192)];
            int len = br.read(buf, 0, buf.length);
            if (len > 0) return new String(buf, 0, len).toLowerCase();
        } catch (Exception ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
