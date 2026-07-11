package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.agenttoolbox.skills.SkillManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具管理器 - 注册和管理所有工具
 */
public class ToolManager {
    
    private Map<String, Tool> tools = new HashMap<>();
    private static ToolManager instance;
    private Context context;
    
    private ToolManager() {}
    
    public static synchronized ToolManager getInstance() {
        if (instance == null) {
            instance = new ToolManager();
        }
        return instance;
    }
    
    public void init(Context ctx) {
        if (context != null) return;
        context = ctx.getApplicationContext();
        ApkMcpClient.getInstance().setContext(ctx);
        
        // 注册所有内置工具
        registerTool(new MathCalculatorTool());
        registerTool(new HttpRequestTool());
        registerTool(new FileReadTool());
        registerTool(new FileWriteTool());
        registerTool(new FileListTool());
        registerTool(new FileSearchTool());
        registerTool(new ShellTool());
        registerTool(new CmdTool());
        registerTool(new PythonTool(context));
        registerTool(new ShTool());
        registerTool(new WebTool());
        registerTool(new AskTool());
        
        // 注册 GM 工具（内存修改相关）
        registerTool(new RootStatusTool());
        registerTool(new ProcessListTool(context));
        registerTool(new AttachProcessTool());
        registerTool(new MemorySearchTool());
        registerTool(new MemoryWriteTool());
        registerTool(new MemoryReadTool());
        registerTool(new MemoryFreezeTool());
        registerTool(new AobSearchTool());
        registerTool(new LuaExecuteTool(context));
        
        // 注册 APK MCP 工具（从 MT 管理器动态拉取）
        mergeApkTools();

        // 注册技能知识读取工具（始终可用，skill 内部懒加载）
        registerTool(new SkillReadTool());

        // 发现并接入 skills（assets 内置 + 运行时目录），注册其中的工具
        SkillManager.getInstance().init(context);
    }
    
    /**
     * 注册工具
     */
    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 批量移除工具（用于 skills 热加载时清掉旧技能工具）
     */
    public void removeTools(Collection<String> names) {
        for (String n : names) tools.remove(n);
    }
    
    /**
     * 从 MT 管理器 APK MCP 动态拉取工具并注册
     */
    public void mergeApkTools() {
        ApkMcpClient client = ApkMcpClient.getInstance();
        if (!client.isEnabled()) return;
        
        if (!client.connect()) {
            android.util.Log.w("ToolManager", "APK MCP 连接失败，跳过工具合并");
            return;
        }
        
        JSONArray remoteTools = client.getRemoteTools();
        if (remoteTools == null || remoteTools.length() == 0) return;
        
        int count = 0;
        for (int i = 0; i < remoteTools.length(); i++) {
            try {
                JSONObject toolDef = remoteTools.getJSONObject(i);
                String name = toolDef.optString("name", "");
                if (!name.startsWith("mt_apk_")) continue;
                
                String desc = toolDef.optString("description", "");
                JSONObject inputSchema = toolDef.optJSONObject("inputSchema");
                if (inputSchema == null) inputSchema = new JSONObject();
                
                ApkMcpToolWrapper wrapper = new ApkMcpToolWrapper(name, desc, inputSchema);
                tools.put(name, wrapper);
                count++;
            } catch (JSONException e) {
                // skip malformed tool
            }
        }
        android.util.Log.i("ToolManager", "已合并 " + count + " 个 APK MCP 工具");
    }
    
    /**
     * 重新拉取 APK MCP 工具（用于 MT 服务启动后手动刷新）
     */
    public void reloadApkTools() {
        // 移除旧的 APK 工具
        java.util.Iterator<String> it = tools.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith("mt_apk_")) it.remove();
        }
        // 重新连接并合并
        ApkMcpClient.getInstance().connect();
        mergeApkTools();
    }
    
    /**
     * 获取工具
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }
    
    /**
     * 获取所有工具列表（MCP格式）
     */
    public JSONArray getToolsList() {
        // 懒加载：如果当前没有任何 mt_apk_* 工具，尝试从 MT 拉取（可能启动时 MT 未运行）
        boolean hasMtTools = false;
        for (Tool t : tools.values()) {
            if (t.getName().startsWith("mt_apk_")) { hasMtTools = true; break; }
        }
        if (!hasMtTools) {
            mergeApkTools();
        }

        JSONArray result = new JSONArray();
        for (Tool tool : tools.values()) {
            try {
                JSONObject toolObj = new JSONObject();
                toolObj.put("name", tool.getName());
                toolObj.put("description", tool.getDescription());
                toolObj.put("inputSchema", tool.getInputSchema());
                result.put(toolObj);
            } catch (JSONException e) {
                // 正常情况下不会发生
                e.printStackTrace();
            }
        }
        return result;
    }
    
    /**
     * 生成工具列表的系统提示词，发送给AI使其知道可用工具
     * 格式：纯 JSON 协议
     */
    public String getSystemPrompt() {
        try {
            // 从 assets 加载静态提示词模板
            JSONObject prompt = loadPromptTemplate();
            if (prompt == null) return "{}";

            // 注入动态工具列表
            JSONArray toolsArray = getToolsList();
            JSONArray tools = new JSONArray();
            for (int i = 0; i < toolsArray.length(); i++) {
                JSONObject tool = toolsArray.getJSONObject(i);
                String name = tool.optString("name", "");
                String desc = tool.optString("description", "");

                JSONObject t = new JSONObject();
                t.put("name", name);
                t.put("description", desc);

                JSONObject schema = tool.optJSONObject("inputSchema");
                if (schema != null) {
                    JSONObject properties = schema.optJSONObject("properties");
                    JSONArray required = schema.optJSONArray("required");
                    if (properties != null && properties.length() > 0) {
                        JSONArray params = new JSONArray();
                        java.util.Iterator<String> keys = properties.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            JSONObject prop = properties.optJSONObject(key);
                            String type = prop != null ? prop.optString("type", "string") : "string";
                            String pdesc = prop != null ? prop.optString("description", "") : "";
                            boolean isRequired = false;
                            if (required != null) {
                                for (int r = 0; r < required.length(); r++) {
                                    if (required.getString(r).equals(key)) { isRequired = true; break; }
                                }
                            }
                            String def = prop != null && prop.has("default") ? prop.optString("default", "") : "";

                            JSONObject p = new JSONObject();
                            p.put("name", key);
                            p.put("type", type);
                            p.put("required", isRequired);
                            if (def.length() > 0) p.put("default", def);
                            p.put("description", pdesc);
                            params.put(p);
                        }
                        t.put("params", params);
                    } else {
                        t.put("params", new JSONArray());
                    }
                } else {
                    t.put("params", new JSONArray());
                }
                tools.put(t);
            }
            prompt.put("tools", tools);

            // 注入已加载技能摘要（完整知识由 skill_read 按需获取）
            try {
                prompt.put("loaded_skills", SkillManager.getInstance().getSkillSummaries());
            } catch (JSONException ignore) {}

            return prompt.toString();

        } catch (JSONException e) {
            e.printStackTrace();
            return "{}";
        }
    }

    private JSONObject promptTemplateCache = null;

    /**
     * 从 assets/system_prompt_template.json 加载静态提示词模板（缓存）
     */
    private JSONObject loadPromptTemplate() {
        if (promptTemplateCache != null) return promptTemplateCache;
        try {
            java.io.InputStream is = context.getAssets().open("system_prompt_template.json");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            promptTemplateCache = new JSONObject(sb.toString());
            return promptTemplateCache;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 调用工具
     */
    public JSONObject callTool(String name, JSONObject arguments) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject contentItem = new JSONObject();
        
        try {
            // APK MCP 工具：转发到 MT 管理器
            if (name.startsWith("mt_apk_")) {
                ApkMcpClient client = ApkMcpClient.getInstance();
                // mt_apk_status：检查连接状态，不依赖 MT 端是否有此工具
                if ("mt_apk_status".equals(name)) {
                    StringBuilder diag = new StringBuilder();
                    diag.append("APK MCP 诊断报告\n");
                    diag.append("=================\n");
                    diag.append("目标地址: ").append(client.getUrl()).append("\n");

                    // 1) TCP socket 连通性测试
                    boolean socketOk = false;
                    try {
                        java.net.Socket sock = new java.net.Socket();
                        sock.connect(new java.net.InetSocketAddress("127.0.0.1", 8787), 2000);
                        sock.close();
                        socketOk = true;
                        diag.append("TCP 端口 8787: ✅ 已开放（服务在监听）\n");
                    } catch (Exception e) {
                        diag.append("TCP 端口 8787: ❌ 连接失败 — ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                    }

                    // 2) HTTP 连接测试
                    if (socketOk) {
                        diag.append("\nHTTP 握手尝试: ");
                        try {
                            boolean ok = client.connect();
                            diag.append(ok ? "✅ 成功" : "❌ 失败").append("\n");
                            diag.append("已注册工具数: ").append(client.getRemoteTools().length()).append("\n");
                            if (ok) {
                                diag.append("\n✅ 服务正常，可调用 mt_apk_* 工具");
                            } else {
                                diag.append("\n⚠️ 端口通了但 HTTP 握手失败 — 可能协议/路径不匹配");
                            }
                        } catch (Exception e2) {
                            diag.append("❌ 异常 — ").append(e2.getClass().getSimpleName()).append(": ").append(e2.getMessage()).append("\n");
                        }
                    } else {
                        diag.append("\n❌ 无法连接到 MT APK MCP 服务\n请在 MT 管理器中打开侧拉栏 → 工具 → APK MCP，点击「启动」");
                    }

                    contentItem.put("type", "text");
                    contentItem.put("text", diag.toString());
                    content.put(contentItem);
                    result.put("content", content);
                    return result;
                }
                return client.callTool(name, arguments);
            }

            // skills/list 和 skills/reload 既是 MCP 方法也支持 tools/call 调用
            if ("skills/list".equals(name)) {
                com.example.agenttoolbox.skills.SkillManager sm = com.example.agenttoolbox.skills.SkillManager.getInstance();
                JSONArray list = sm.getSkillSummaries();
                contentItem.put("type", "text");
                contentItem.put("text", "已加载 " + list.length() + " 个技能:\n" + list.toString(2));
                content.put(contentItem);
                result.put("content", content);
                return result;
            }
            if ("skills/reload".equals(name)) {
                com.example.agenttoolbox.skills.SkillManager sm = com.example.agenttoolbox.skills.SkillManager.getInstance();
                sm.reload();
                JSONArray list = sm.getSkillSummaries();
                contentItem.put("type", "text");
                contentItem.put("text", "技能已重载，当前 " + list.length() + " 个:\n" + list.toString(2));
                content.put(contentItem);
                result.put("content", content);
                return result;
            }

            Tool tool = tools.get(name);
            if (tool == null) {
                result.put("isError", true);
                contentItem.put("type", "text");
                contentItem.put("text", "工具不存在: " + name);
                content.put(contentItem);
                result.put("content", content);
                return result;
            }
            
            String output = tool.execute(arguments);
            result.put("isError", false);
            contentItem.put("type", "text");
            contentItem.put("text", output);
            content.put(contentItem);
            result.put("content", content);
            
        } catch (Exception e) {
            try {
                result.put("isError", true);
                contentItem.put("type", "text");
                contentItem.put("text", "工具执行失败: " + e.getMessage());
                content.put(contentItem);
                result.put("content", content);
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }
    
    /**
     * APK MCP 工具包装器 — 将 MT 管理器的工具包装为 Tool 接口
     */
    private static class ApkMcpToolWrapper implements Tool {
        private final String name;
        private final String description;
        private final JSONObject inputSchema;
        
        ApkMcpToolWrapper(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public JSONObject getInputSchema() {
            return inputSchema;
        }
        
        @Override
        public String execute(JSONObject arguments) {
            // 不应该直接调用 wrapper，应该通过 callTool 路由
            JSONObject result = ApkMcpClient.getInstance().callTool(name, arguments);
            return result != null ? result.toString() : "APK 工具调用失败";
        }
    }
    
}
