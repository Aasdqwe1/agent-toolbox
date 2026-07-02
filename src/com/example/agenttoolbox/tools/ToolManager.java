package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

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
        
        // 注册所有内置工具
        registerTool(new MathCalculatorTool());
        registerTool(new HttpRequestTool());
        registerTool(new FileReadTool());
        registerTool(new FileWriteTool());
        registerTool(new FileListTool());
        registerTool(new ShellTool());
        registerTool(new CmdTool());
        registerTool(new PythonTool());
        registerTool(new ShTool());
        registerTool(new WebTool());
        
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
    }
    
    /**
     * 注册工具
     */
    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
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
     */
    public String getSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【环境初始化 · MCP 工具箱】\n");
        prompt.append("\n");
        prompt.append("你是一个运行在 Android 端的 MCP 工具执行环境。优先使用工具完成任务，无法用工具完成或已获得足够信息时，直接用自然语言回答。\n");
        prompt.append("\n");
        prompt.append("---\n");
        prompt.append("\n");
        prompt.append("**工具列表**\n");
        prompt.append("\n");
        
        try {
            JSONArray toolsArray = getToolsList();
            
            prompt.append("| 工具 | 功能 |\n");
            prompt.append("|------|------|\n");
            
            for (int i = 0; i < toolsArray.length(); i++) {
                JSONObject tool = toolsArray.getJSONObject(i);
                String name = tool.optString("name", "");
                String desc = tool.optString("description", "");
                prompt.append("| `").append(name).append("` | ").append(desc).append(" |\n");
            }
            
            prompt.append("\n");
            prompt.append("**file_write 模式速查**\n");
            prompt.append("\n");
            prompt.append("| 模式 | 作用 |\n");
            prompt.append("|------|------|\n");
            prompt.append("| `write` | 覆盖写入整个文件 |\n");
            prompt.append("| `insert` | 在指定行后插入文本 |\n");
            prompt.append("| `replace` | 替换指定行的内容 |\n");
            prompt.append("| `delete` | 删除指定行 |\n");
            prompt.append("| `append_line` | 在文件末尾追加一行 |\n");
            prompt.append("| `prepend_line` | 在文件开头插入一行 |\n");
            prompt.append("\n");
            prompt.append("> 💡 行号从 1 开始。`end_line` 可选，不填则默认与 `line` 相同（单行操作）。\n");
            prompt.append("\n");
            prompt.append("---\n");
            prompt.append("\n");
            prompt.append("**核心规则（严格遵守）**\n");
            prompt.append("\n");
            prompt.append("1. **调用工具时，必须输出一个严格的 JSON-RPC 2.0 请求，每次只能输出一个。**\n");
            prompt.append("   - 格式模板：\n");
            prompt.append("     ```json\n");
            prompt.append("     {\n");
            prompt.append("       \"jsonrpc\": \"2.0\",\n");
            prompt.append("       \"id\": \"1\",\n");
            prompt.append("       \"method\": \"tools/call\",\n");
            prompt.append("       \"params\": {\n");
            prompt.append("         \"name\": \"工具名\",\n");
            prompt.append("         \"arguments\": { ... }\n");
            prompt.append("       }\n");
            prompt.append("     }\n");
            prompt.append("     ```\n");
            prompt.append("   - id 用递增数字（1, 2, 3…）。\n");
            prompt.append("   - 不要添加 result 等其他字段。\n");
            prompt.append("2. **绝对禁止同时输出多个 JSON 对象或 JSON 数组。**\n");
            prompt.append("3. **不需要调用工具时，直接用自然语言回答，禁止输出任何 JSON。**\n");
            prompt.append("4. **多步操作需串行执行：**输出一个 JSON 请求后，等待系统返回结果，再决定下一步或给出最终回答。\n");
            prompt.append("5. **文件路径安全限制：**\n");
            prompt.append("   - 允许的写入路径仅限：`/sdcard/Download/`、`/sdcard/Documents/`、`/sdcard/Pictures/`、`/sdcard/DCIM/`、`/sdcard/Movies/` 及其子目录。\n");
            prompt.append("   - 路径中禁止包含 `..`，不可进行路径遍历。\n");
            prompt.append("6. **错误处理：**若工具返回 `isError: true`，应向用户说明错误原因，并根据提示修正参数后重试。\n");
            prompt.append("\n");
            prompt.append("---\n");
            prompt.append("\n");
            prompt.append("**工具返回结果读取**\n");
            prompt.append("\n");
            prompt.append("- 结果内容在 `result.content[].text` 中。\n");
            prompt.append("- `result.isError` 为 true 表示失败，`text` 中包含错误详情。\n");
            prompt.append("\n");
            prompt.append("请回复\"已接收工具协议，可以开始任务\"，继续接收用户问题。");
            
        } catch (JSONException e) {
            e.printStackTrace();
            prompt.append("工具列表加载失败。");
        }
        
        return prompt.toString();
    }
    
    /**
     * 调用工具
     */
    public JSONObject callTool(String name, JSONObject arguments) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject contentItem = new JSONObject();
        
        try {
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
                // 正常情况下不会发生
                ex.printStackTrace();
            }
        }
        
        return result;
    }
    
}
