package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Web 网页获取工具
 */
public class WebTool implements Tool {

    @Override
    public String getName() {
        return "web";
    }

    @Override
    public String getDescription() {
        return "获取网页 HTML 内容，支持 GET 请求，可解析标题和提取文本";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject url = new JSONObject();
            url.put("type", "string");
            url.put("description", "目标网页 URL，如 https://www.example.com");
            properties.put("url", url);

            JSONObject mode = new JSONObject();
            mode.put("type", "string");
            mode.put("description", "获取模式：html(原始HTML)、text(纯文本)、title(仅标题)、meta(元信息)");
            mode.put("default", "text");
            properties.put("mode", mode);

            JSONObject timeout = new JSONObject();
            timeout.put("type", "integer");
            timeout.put("description", "超时时间（秒），默认 30");
            timeout.put("default", 30);
            properties.put("timeout", timeout);

            schema.put("properties", properties);

            JSONArray requiredArray = new JSONArray();
            requiredArray.put("url");
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String urlStr = arguments.getString("url");
        String mode = arguments.has("mode") ? arguments.getString("mode") : "text";
        int timeout = arguments.has("timeout") ? arguments.getInt("timeout") : 30;

        if (urlStr == null || urlStr.trim().isEmpty()) {
            throw new Exception("URL 不能为空");
        }

        // 自动补全 https://
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            urlStr = "https://" + urlStr;
        }

        StringBuilder result = new StringBuilder();
        result.append("URL: ").append(urlStr).append("\n");
        result.append("模式: ").append(mode).append("\n\n");

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");

            int responseCode = conn.getResponseCode();
            result.append("状态码: ").append(responseCode).append("\n\n");

            if (responseCode != 200) {
                result.append("错误: HTTP ").append(responseCode);
                return result.toString();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
            reader.close();
            conn.disconnect();

            String content = html.toString();

            switch (mode) {
                case "html":
                    result.append("HTML 内容:\n").append(content);
                    break;
                case "title":
                    result.append("页面标题: ").append(extractTitle(content));
                    break;
                case "meta":
                    result.append("页面标题: ").append(extractTitle(content)).append("\n");
                    result.append("Meta 描述: ").append(extractMetaDescription(content)).append("\n");
                    result.append("Meta 关键词: ").append(extractMetaKeywords(content));
                    break;
                case "text":
                default:
                    result.append("页面标题: ").append(extractTitle(content)).append("\n\n");
                    result.append("正文内容:\n").append(extractText(content));
                    break;
            }

        } catch (Exception e) {
            throw new Exception("获取网页失败: " + e.getMessage());
        }

        return result.toString();
    }

    private String extractTitle(String html) {
        if (html == null) return "未知";
        int start = html.indexOf("<title");
        if (start == -1) return "无标题";
        int tagEnd = html.indexOf(">", start);
        if (tagEnd == -1) return "无标题";
        int contentStart = tagEnd + 1;
        int end = html.indexOf("</title>", contentStart);
        if (end == -1) return "无标题";
        return stripTags(html.substring(contentStart, end)).trim();
    }

    private String extractMetaDescription(String html) {
        if (html == null) return "无";
        String pattern = "name=\"description\"";
        int idx = html.indexOf(pattern);
        if (idx == -1) pattern = "name='description'";
        if (idx == -1) return "无";
        int contentStart = html.indexOf("content=\"", idx);
        if (contentStart == -1) {
            contentStart = html.indexOf("content='", idx);
            if (contentStart == -1) return "无";
            contentStart += 9;
            int end = html.indexOf("'", contentStart);
            if (end == -1) return "无";
            return stripTags(html.substring(contentStart, end)).trim();
        } else {
            contentStart += 10;
            int end = html.indexOf("\"", contentStart);
            if (end == -1) return "无";
            return stripTags(html.substring(contentStart, end)).trim();
        }
    }

    private String extractMetaKeywords(String html) {
        if (html == null) return "无";
        String pattern = "name=\"keywords\"";
        int idx = html.indexOf(pattern);
        if (idx == -1) pattern = "name='keywords'";
        if (idx == -1) return "无";
        int contentStart = html.indexOf("content=\"", idx);
        if (contentStart == -1) {
            contentStart = html.indexOf("content='", idx);
            if (contentStart == -1) return "无";
            contentStart += 9;
            int end = html.indexOf("'", contentStart);
            if (end == -1) return "无";
            return stripTags(html.substring(contentStart, end)).trim();
        } else {
            contentStart += 10;
            int end = html.indexOf("\"", contentStart);
            if (end == -1) return "无";
            return stripTags(html.substring(contentStart, end)).trim();
        }
    }

    private String extractText(String html) {
        if (html == null) return "";
        // 移除 script 和 style 标签及其内容
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        // 移除所有 HTML 标签
        html = stripTags(html);
        // 清理多余空白
        html = html.replaceAll("[ \\t]+", " ");
        html = html.replaceAll("\\n{3,}", "\n\n");
        return html.trim();
    }

    private String stripTags(String html) {
        if (html == null) return "";
        boolean inTag = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
                sb.append(' ');
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
