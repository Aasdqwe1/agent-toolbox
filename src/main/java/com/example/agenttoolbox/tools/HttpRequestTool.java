package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTP请求工具
 */
public class HttpRequestTool implements Tool {

    @Override
    public String getName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "通用HTTP请求工具，支持GET/POST调用第三方接口";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            
            JSONObject method = new JSONObject();
            method.put("type", "string");
            method.put("description", "请求方法：GET/POST/PUT/DELETE");
            properties.put("method", method);
            
            JSONObject url = new JSONObject();
            url.put("type", "string");
            url.put("description", "目标接口URL");
            properties.put("url", url);
            
            JSONObject headers = new JSONObject();
            headers.put("type", "object");
            headers.put("description", "请求头键值对");
            properties.put("headers", headers);
            
            JSONObject body = new JSONObject();
            body.put("type", "object");
            body.put("description", "POST请求体（JSON格式）");
            properties.put("body", body);
            
            schema.put("properties", properties);
            
            String[] required = {"method", "url"};
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
        String method = arguments.getString("method").toUpperCase();
        String urlStr = arguments.getString("url");
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        // 设置请求头
        if (arguments.has("headers")) {
            JSONObject headers = arguments.getJSONObject("headers");
            java.util.Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                conn.setRequestProperty(key, headers.getString(key));
            }
        }
        
        // 设置请求体
        if (arguments.has("body") && !method.equals("GET")) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(arguments.getJSONObject("body").toString().getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        
        int responseCode = conn.getResponseCode();
        BufferedReader reader;
        if (responseCode >= 200 && responseCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
        }
        
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        
        return "HTTP " + responseCode + "\n" + response.toString();
    }

}
