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
        prompt.append("【环境初始化 · MCP 工具箱 v3】\n\n");
        prompt.append("你是运行在 Android 设备上的 AI 助手，拥有以下工具。通过输出 JSON-RPC 2.0 请求来调用工具。\n\n");
        prompt.append("---\n\n");

        try {
            JSONArray toolsArray = getToolsList();

            // === 动态生成工具详细说明（含参数） ===
            prompt.append("## 工具详细说明\n\n");
            for (int i = 0; i < toolsArray.length(); i++) {
                JSONObject tool = toolsArray.getJSONObject(i);
                String name = tool.optString("name", "");
                String desc = tool.optString("description", "");
                prompt.append("### ").append(i + 1).append(". `").append(name).append("` — ").append(desc).append("\n");

                JSONObject schema = tool.optJSONObject("inputSchema");
                if (schema != null) {
                    JSONObject properties = schema.optJSONObject("properties");
                    JSONArray required = schema.optJSONArray("required");
                    if (properties != null && properties.length() > 0) {
                        prompt.append("   参数：\n");
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
                            prompt.append("   - `").append(key).append("` (").append(type);
                            prompt.append(isRequired ? ", 必填" : ", 可选");
                            if (def.length() > 0) prompt.append(", 默认: ").append(def);
                            prompt.append("): ").append(pdesc).append("\n");
                        }
                    } else {
                        prompt.append("   参数：无\n");
                    }
                } else {
                    prompt.append("   参数：无\n");
                }
                prompt.append("\n");
            }

            // === file_write 模式速查 ===
            prompt.append("### file_write 模式速查\n\n");
            prompt.append("| 模式 | 作用 |\n");
            prompt.append("|------|------|\n");
            prompt.append("| `write` | 覆盖写入整个文件 |\n");
            prompt.append("| `insert` | 在指定行后插入文本 |\n");
            prompt.append("| `replace` | 替换指定行的内容 |\n");
            prompt.append("| `delete` | 删除指定行 |\n");
            prompt.append("| `append_line` | 在文件末尾追加一行 |\n");
            prompt.append("| `prepend_line` | 在文件开头插入一行 |\n\n");
            prompt.append("> 行号从 1 开始。`end_line` 可选，不填则默认与 `line` 相同。\n\n");
            prompt.append("---\n\n");

            // === GM 工具使用流程 ===
            prompt.append("## GM 内存修改工具使用流程\n\n");
            prompt.append("修改游戏/应用内存时，**必须按以下顺序操作**：\n\n");
            prompt.append("1. **检查 Root** → 调用 `gm_root_status`\n");
            prompt.append("2. **获取进程列表** → 调用 `gm_process_list`，从返回结果中找到目标应用的 pid\n");
            prompt.append("3. **附加进程** → 调用 `gm_attach_process`，传入 pid\n");
            prompt.append("4. **搜索数值** → 调用 `gm_memory_search`，传入当前数值和数据类型\n");
            prompt.append("5. **读取/写入** → 根据搜索到的地址，调用 `gm_memory_read` 或 `gm_memory_write`\n");
            prompt.append("6. **冻结（可选）** → 调用 `gm_memory_freeze` 锁定数值防止变化\n\n");

            prompt.append("### 数据类型说明\n\n");
            prompt.append("| 类型 | 字节数 | 适用场景 |\n");
            prompt.append("|------|--------|----------|\n");
            prompt.append("| byte | 1 | 小数值 0~255 |\n");
            prompt.append("| word | 2 | 中等数值 0~65535 |\n");
            prompt.append("| dword | 4 | **最常用**，金币/血量/等级等整数 |\n");
            prompt.append("| qword | 8 | 64位大整数 |\n");
            prompt.append("| float | 4 | 小数，坐标/速度/角度等 |\n");
            prompt.append("| double | 8 | 高精度小数 |\n\n");

            prompt.append("### Lua 脚本（gm_lua_execute）\n\n");
            prompt.append("支持 GG 修改器 API，常用函数：\n");
            prompt.append("- `gg.searchNumber(\"值\", gg.TYPE_DWORD)` — 搜索数值\n");
            prompt.append("- `gg.getResults(count)` — 获取搜索结果\n");
            prompt.append("- `gg.editAll(\"新值\", gg.TYPE_DWORD)` — 修改所有结果\n");
            prompt.append("- `gg.toast(\"消息\")` — 显示提示\n\n");
            prompt.append("---\n\n");

            // === 调用示例 ===
            prompt.append("## 调用示例\n\n");
            prompt.append("### 示例：修改游戏金币为 99999\n\n");
            prompt.append("步骤1 — 检查 Root：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/call\",\"params\":{\"name\":\"gm_root_status\",\"arguments\":{}}}\n");
            prompt.append("```\n\n");
            prompt.append("步骤2 — 获取进程列表：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/call\",\"params\":{\"name\":\"gm_process_list\",\"arguments\":{}}}\n");
            prompt.append("```\n\n");
            prompt.append("步骤3 — 附加进程（假设 pid=1234）：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"tools/call\",\"params\":{\"name\":\"gm_attach_process\",\"arguments\":{\"pid\":1234}}}\n");
            prompt.append("```\n\n");
            prompt.append("步骤4 — 搜索当前金币值（假设=5000）：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"method\":\"tools/call\",\"params\":{\"name\":\"gm_memory_search\",\"arguments\":{\"value\":\"5000\",\"type\":\"dword\"}}}\n");
            prompt.append("```\n\n");
            prompt.append("步骤5 — 写入新值（假设搜索到地址 0x12345678）：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"method\":\"tools/call\",\"params\":{\"name\":\"gm_memory_write\",\"arguments\":{\"address\":\"0x12345678\",\"value\":\"99999\",\"type\":\"dword\"}}}\n");
            prompt.append("```\n\n");
            prompt.append("---\n\n");

            // === 核心规则 ===
            prompt.append("## 核心规则\n\n");
            prompt.append("1. **每次只输出一个 JSON-RPC 请求**，格式：\n");
            prompt.append("```json\n");
            prompt.append("{\"jsonrpc\":\"2.0\",\"id\":\"递增数字\",\"method\":\"tools/call\",\"params\":{\"name\":\"工具名\",\"arguments\":{...}}}\n");
            prompt.append("```\n");
            prompt.append("2. **串行执行**：输出请求后等待结果返回，再决定下一步。\n");
            prompt.append("3. **不需要工具时直接用自然语言回答**，禁止输出 JSON。\n");
            prompt.append("4. **GM 工具必须按流程顺序使用**，不能跳过附加进程直接搜索。\n");
            prompt.append("5. **地址格式**：十六进制，如 `0x7FFF1234`。\n");
            prompt.append("6. **文件写入路径限制**：仅限 `/sdcard/Download/`、`/sdcard/Documents/` 等安全目录。\n");
            prompt.append("7. **错误处理**：若返回 `isError: true`，说明原因并修正参数重试。\n\n");
            prompt.append("---\n\n");
            prompt.append("## 结果读取\n\n");
            prompt.append("- 结果在 `result.content[].text` 中。\n");
            prompt.append("- `result.isError` 为 true 表示失败。\n\n");
            prompt.append("请回复\"已接收工具协议，可以开始任务\"。");

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
