package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
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
        return "获取网页 HTML 内容，支持 GET 请求，可解析标题和提取文本，也可抓取 JSON API";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject url = new JSONObject();
            url.put("type", "string");
            url.put("description", "目标网页 URL，如 https://www.example.com 或 https://api.example.com/data");
            properties.put("url", url);

            JSONObject mode = new JSONObject();
            mode.put("type", "string");
            mode.put("description", "获取模式：html(原始HTML)、text(纯文本)、title(仅标题)、meta(元信息)、raw(原样输出，用于 JSON/文本 API)");
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

        if (urlStr == null) {
            throw new Exception("URL 不能为空");
        }
        urlStr = urlStr.trim();
        if (urlStr.isEmpty()) {
            throw new Exception("URL 不能为空");
        }

        // 自动补全 https://
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            urlStr = "https://" + urlStr;
        }

        StringBuilder result = new StringBuilder();
        result.append("请求 URL: ").append(urlStr).append("\n");
        result.append("模式: ").append(mode).append("\n\n");

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 AgentToolbox/1.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            String contentType = conn.getContentType();
            result.append("HTTP 状态码: ").append(responseCode).append("\n");
            result.append("Content-Type: ").append(contentType == null ? "(无)" : contentType).append("\n\n");

            // 非 2xx：仍然读取错误流并提示，不要直接返回 "错误: HTTP xxx" 而无内容
            if (responseCode < 200 || responseCode >= 300) {
                String errBody = readStream(conn.getErrorStream());
                result.append("---- 服务器响应（非 2xx）----\n");
                if (errBody == null || errBody.isEmpty()) {
                    result.append("(空响应体)\n");
                } else {
                    int max = Math.min(errBody.length(), 4000);
                    result.append(errBody.substring(0, max));
                    if (errBody.length() > max) {
                        result.append("\n...(已截断，共 ").append(errBody.length()).append(" 字符)");
                    }
                }
                result.append("\n\n---- 诊断提示 ----\n");
                result.append("  1. 检查 URL 是否正确（拼写、协议）\n");
                result.append("  2. 4xx: 检查参数/鉴权；5xx: 服务端故障\n");
                result.append("  3. 可用 http_request 工具尝试 POST / 加 headers\n");
                result.append("  4. 3xx: 可能重定向，已自动跟随；可检查最终地址\n");
                conn.disconnect();
                return result.toString();
            }

            String body = readStream(conn.getInputStream());
            conn.disconnect();

            if (body == null || body.trim().isEmpty()) {
                result.append("(返回空内容)\n\n");
                result.append("---- 诊断提示 ----\n");
                result.append("服务器返回 HTTP 200，但响应体为空。可能原因:\n");
                result.append("  1. API 不需要返回体\n");
                result.append("  2. 需要鉴权（Cookie/Token），请通过 http_request 工具附加 headers\n");
                result.append("  3. Content-Type 编码问题\n");
                return result.toString();
            }

            // 根据 mode 决定输出形态
            if ("raw".equals(mode)) {
                result.append("响应内容:\n").append(body);
                return result.toString();
            }

            // 简单 JSON 检测：若内容看起来是 JSON，直接作为 raw 输出
            String trimmed = body.trim();
            boolean looksLikeJson =
                    (trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"));
            if (looksLikeJson) {
                result.append("检测到 JSON 响应，原样输出:\n").append(body);
                return result.toString();
            }

            switch (mode) {
                case "html":
                    result.append("HTML 内容:\n").append(body);
                    break;
                case "title":
                    result.append("页面标题: ").append(extractTitle(body));
                    break;
                case "meta":
                    result.append("页面标题: ").append(extractTitle(body)).append("\n");
                    result.append("Meta 描述: ").append(extractMetaDescription(body)).append("\n");
                    result.append("Meta 关键词: ").append(extractMetaKeywords(body));
                    break;
                case "text":
                default:
                    result.append("页面标题: ").append(extractTitle(body)).append("\n\n");
                    String text = extractText(body);
                    if (text.length() > 8000) {
                        result.append("正文（已截断，共 ").append(text.length()).append(" 字符）:\n");
                        result.append(text.substring(0, 8000));
                        result.append("\n...(已截断)");
                    } else {
                        result.append("正文内容:\n").append(text);
                    }
                    break;
            }

        } catch (Exception e) {
            throw new Exception("获取网页失败: " + e.getMessage()
                + "；可先用 shell ping <host> 或 http_request 工具排查");
        }

        return result.toString();
    }

    /**
     * 从 InputStream 读取全部内容，尝试按 UTF-8/系统默认编码解析。
     */
    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return "(读取流时发生异常: " + e.getMessage() + ")";
        }
    }

    private static String extractTitle(String html) {
        if (html == null) return "未知";
        int start = html.indexOf("<title");
        if (start == -1) start = html.indexOf("<TITLE");
        if (start == -1) return "无标题";
        int tagEnd = html.indexOf(">", start);
        if (tagEnd == -1) return "无标题";
        int contentStart = tagEnd + 1;
        int end = html.indexOf("</title>", contentStart);
        if (end == -1) end = html.indexOf("</TITLE>", contentStart);
        if (end == -1) return "无标题";
        return stripTags(html.substring(contentStart, end)).trim();
    }

    private static String extractMetaDescription(String html) {
        if (html == null) return "无";
        int idx = html.indexOf("name=\"description\"");
        if (idx == -1) idx = html.indexOf("name='description'");
        if (idx == -1) idx = html.indexOf("NAME=\"description\"");
        if (idx == -1) return "无";
        int contentStart = html.indexOf("content=\"", idx);
        if (contentStart == -1) contentStart = html.indexOf("content='", idx);
        if (contentStart == -1) return "无";
        char quote = html.charAt(contentStart + 8);  // 'c','o','n','t','e','n','t','=' quote
        contentStart += 10;
        int end = html.indexOf(quote, contentStart);
        if (end == -1) return "无";
        return stripTags(html.substring(contentStart, end)).trim();
    }

    private static String extractMetaKeywords(String html) {
        if (html == null) return "无";
        int idx = html.indexOf("name=\"keywords\"");
        if (idx == -1) idx = html.indexOf("name='keywords'");
        if (idx == -1) idx = html.indexOf("NAME=\"keywords\"");
        if (idx == -1) return "无";
        int contentStart = html.indexOf("content=\"", idx);
        if (contentStart == -1) contentStart = html.indexOf("content='", idx);
        if (contentStart == -1) return "无";
        char quote = html.charAt(contentStart + 8);
        contentStart += 10;
        int end = html.indexOf(quote, contentStart);
        if (end == -1) return "无";
        return stripTags(html.substring(contentStart, end)).trim();
    }

    private static String extractText(String html) {
        if (html == null) return "";
        // 移除 script/style/noscript 及其内容（用简单状态机，避免正则性能问题）
        html = removeBlock(html, "script");
        html = removeBlock(html, "style");
        html = removeBlock(html, "noscript");
        // 移除所有 HTML 标签
        html = stripTags(html);
        // 清理多余空白
        html = html.replaceAll("[ \\t\\x0B\\f]+", " ");
        html = html.replaceAll("\\n[ \\t]+", "\n");
        html = html.replaceAll("\\n{3,}", "\n\n");
        return html.trim();
    }

    private static String removeBlock(String html, String tag) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = html.length();
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int closeLen = close.length();
        while (i < n) {
            int s = indexOfIgnoreCase(html, open, i);
            if (s == -1) {
                out.append(html, i, n);
                break;
            }
            out.append(html, i, s);
            // 找到匹配的 </tag>
            int e = indexOfIgnoreCase(html, close, s + open.length());
            if (e == -1) {
                // 无闭合标签，直接截断到末尾
                break;
            }
            i = e + closeLen;
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String s, String needle, int fromIdx) {
        int n = s.length();
        int m = needle.length();
        if (fromIdx + m > n) return -1;
        char first = Character.toLowerCase(needle.charAt(0));
        for (int i = fromIdx; i <= n - m; i++) {
            char c = s.charAt(i);
            if (Character.toLowerCase(c) != first) continue;
            boolean ok = true;
            for (int j = 1; j < m; j++) {
                if (Character.toLowerCase(s.charAt(i + j)) != Character.toLowerCase(needle.charAt(j))) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        boolean inTag = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
                // 只追加一个空白，避免替换为大量换行
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) != '\n') {
                    sb.append(' ');
                }
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
