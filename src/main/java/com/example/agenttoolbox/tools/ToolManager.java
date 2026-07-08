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
        registerTool(new PythonTool(context));
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
        
        // 注册 APK MCP 工具（从 MT 管理器动态拉取）
        mergeApkTools();
    }
    
    /**
     * 注册工具
     */
    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
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
            JSONArray toolsArray = getToolsList();

            JSONObject prompt = new JSONObject();

            // ============================================================
            // 1. 协议与角色
            // ============================================================
            prompt.put("protocol", "JSON-RPC 2.0");
            prompt.put("role", "你是运行在 Android 设备上的 AI 助手，通过 MCP 工具箱为用户提供服务");
            prompt.put("enforce", "所有回复必须是符合 JSON-RPC 2.0 规范的单个纯 JSON 对象。自然语言、markdown、代码块只能放在 JSON 的字符串字段内部（如 result.content、result.instruction），不得出现在 JSON 结构之外");

            // ============================================================
            // 2. 消息流程（一轮对话的完整生命周期）
            // ============================================================
            JSONObject messageFlow = new JSONObject();
            messageFlow.put("首轮", "服务端发送 initialize（含 system 规则和 user 消息），你回复 init_reply 或直接开始执行任务");
            messageFlow.put("工具调用", "你发送 method=tools/call → 服务端执行后返回 result（含工具结果和可选 plan 进度）");
            messageFlow.put("计划推进", "服务端发送 type=system 指令（含 action/task/plan/instruction）→ 你按指令执行 → 完成后在 result 中加 plan_update 推进");
            messageFlow.put("文本回复", "你发送 result.type=reply → 服务端提取内容显示给用户 → 对话结束（除非有 canContinue）");
            messageFlow.put("格式错误", "服务端发送 type=system action=format_error → 你修正后重新输出");
            prompt.put("message_flow", messageFlow);

            // ============================================================
            // 3. 回复格式（LLM → Server 输出）
            // ============================================================
            JSONArray formats = new JSONArray();
            
            // 3a. 文本回复
            JSONObject fmtReply = new JSONObject();
            fmtReply.put("type", "reply");
            fmtReply.put("desc", "文本回答：result.type=reply，content 放回答内容。如有待办计划，在 result 中附加 plan_update 字段");
            fmtReply.put("example", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", new JSONObject()
                            .put("type", "reply")
                            .put("content", "你的回答内容")
                            .put("plan_update", new JSONObject().put("action", "complete_task").put("task_id", "T001")))
                    .put("id", 1001));
            formats.put(fmtReply);

            // 3b. 工具调用
            JSONObject fmtTool = new JSONObject();
            fmtTool.put("type", "tool_call");
            fmtTool.put("desc", "调用工具：method=tools/call，params.name=工具名，params.arguments={参数}。id 回带 initialize 的 id");
            fmtTool.put("example", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tools/call")
                    .put("params", new JSONObject()
                            .put("name", "file_read")
                            .put("arguments", new JSONObject().put("path", "/sdcard/Download/test.txt")))
                    .put("id", 1001));
            formats.put(fmtTool);

            // 3c. plan_update 操作类型
            JSONObject planUpdateActions = new JSONObject();
            planUpdateActions.put("complete_task", "标记任务完成：{\"action\":\"complete_task\",\"task_id\":\"T001\"}。不填 task_id 则默认完成当前活跃任务");
            planUpdateActions.put("mark_failed", "标记任务失败：{\"action\":\"mark_failed\",\"task_id\":\"T001\",\"reason\":\"失败原因\"}");
            planUpdateActions.put("update_plan", "替换整个计划：{\"action\":\"update_plan\",\"plan\":{\"tasks\":[...]}}");
            fmtReply.put("plan_update_actions", planUpdateActions);

            prompt.put("reply_formats", formats);

            // ============================================================
            // 4. 核心规则
            // ============================================================
            JSONArray rules = new JSONArray();
            rules.put("jsonrpc 字段：每个回复必须包含 \"jsonrpc\":\"2.0\"");
            rules.put("id 字段：回带 initialize 请求里的 id，所有回复共用同一个 id，不要自创或递增");
            rules.put("单工具调用：每次只能调用一个工具，工具执行后你会收到 JSON-RPC 格式结果，再决定下一步");
            rules.put("plan_update 规则：收到工具结果后，如果 result 中附带 plan 字段（说明有待办计划），你必须在文本回复的 result 中加 plan_update 推进计划，系统不会自动推进");
            rules.put("计划 JSON 位置：{\"tasks\":[...]} 必须放在 result.content 字符串内部，不要作为独立 JSON 输出");
            rules.put("error 处理：收到 error 对象时说明原因并修正参数重试");
            rules.put("JSON 转义：字符串值内的双引号必须转义为 \\\"，Python 代码中优先使用单引号避免冲突");
            rules.put("文件路径：仅限 /sdcard/Download/、/sdcard/Documents/ 等安全目录");
            rules.put("file_write 模式：replace=替换行，insert=行前插入，append=末尾追加。优先用 insert/append 避免行号偏移");
            rules.put("Python：直接调用 python 工具，不要用 shell which python 或 shell python3。禁止 os.system/subprocess/ctypes");
            rules.put("GM 流程：检查Root → 进程列表 → 附加进程 → 搜索 → 读写");
            prompt.put("rules", rules);

            // ============================================================
            // 5. 服务端消息（Server → LLM，首轮后出现）
            // ============================================================
            JSONObject serverMsgs = new JSONObject();
            serverMsgs.put("工具结果", "工具执行结果，附带 plan 进度：{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"结果\"}]},\"plan\":{\"total\":5,\"completed\":1,\"failed\":0,\"active_task\":\"T002\"},\"id\":1001}");
            serverMsgs.put("系统指令", "计划执行指令 type=system：{\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"system\",\"action\":\"execute_task\",\"task\":{\"task_id\":\"T001\",\"content\":\"任务描述\",\"tool_needs\":[\"file_read\"]},\"plan\":{\"total\":5,\"completed\":0},\"instruction\":\"请调用对应工具执行\"},\"id\":1001}");
            serverMsgs.put("格式错误", "格式校验失败 type=system：{\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"system\",\"action\":\"format_error\",\"instruction\":\"错误详情\"},\"id\":1001}");
            prompt.put("server_messages", serverMsgs);

            // ============================================================
            // 6. system_actions（type=system 中 action 字段含义）
            // ============================================================
            JSONObject systemActions = new JSONObject();
            systemActions.put("execute_task", "执行 task 字段指定的任务，调用 task.tool_needs 中对应工具。完成后在 result 中加 plan_update 推进");
            systemActions.put("plan_complete", "所有任务完成，根据 instruction 中的总结信息回复用户");
            systemActions.put("format_error", "格式校验失败，根据 instruction 中的错误详情修正 JSON-RPC 格式后重新输出");
            prompt.put("system_actions", systemActions);

            // ============================================================
            // 7. 工作流状态机（FSM）
            // ============================================================
            JSONObject workflows = new JSONObject();

            JSONObject fileWf = new JSONObject();
            fileWf.put("trigger", "用户要求读取、修改、创建文件时自动激活");
            JSONArray fileStates = new JSONArray();
            fileStates.put("IDLE → 空闲");
            fileStates.put("NEED_READ → 系统自动触发 file_read 读取目标文件");
            fileStates.put("READ_SUCCESS → 文件内容已缓存，你收到内容后分析并决定如何修改");
            fileStates.put("NEED_EDIT → 输出修改后的完整内容（不要调用 file_write）");
            fileStates.put("WRITE_READY → 系统自动触发 file_write 写入");
            fileStates.put("WRITE_DONE → 写入完成");
            fileWf.put("states", fileStates);
            fileWf.put("note", "READ_SUCCESS 阶段收到带行号的文件内容。NEED_EDIT 阶段输出修改后文本即可。路径限白名单：/sdcard/Download/、/sdcard/Documents/ 等");
            workflows.put("file", fileWf);

            JSONObject pyWf = new JSONObject();
            pyWf.put("trigger", "用户要求执行 Python 代码时自动激活");
            JSONArray pyStates = new JSONArray();
            pyStates.put("IDLE → 空闲");
            pyStates.put("NEED_GEN_SCRIPT → 生成 Python 脚本代码");
            pyStates.put("RUN_SCRIPT → 系统自动执行 python 工具");
            pyStates.put("EXEC_SUCCESS → 执行成功，收到结果");
            pyStates.put("EXEC_ERROR → 执行失败，修正代码重试");
            pyWf.put("states", pyStates);
            pyWf.put("note", "Python 3.14 已内嵌。禁止 os.system/subprocess/ctypes。超时 60 秒。优先使用单引号");
            workflows.put("python", pyWf);

            JSONObject shWf = new JSONObject();
            shWf.put("trigger", "用户要求执行 Shell 命令时自动激活");
            JSONArray shStates = new JSONArray();
            shStates.put("IDLE → 空闲");
            shStates.put("NEED_PARSE_CMD → 从用户意图提取/生成 Shell 命令");
            shStates.put("RUN_CMD → 系统自动执行 shell 工具");
            shStates.put("CMD_SUCCESS → 执行成功，收到输出");
            shStates.put("CMD_ERROR → 执行失败，修正命令");
            shWf.put("states", shStates);
            shWf.put("note", "禁止 rm -rf /、su、mount、mkfs、dd if=/dev/zero 等破坏性操作。超时 30 秒");
            workflows.put("shell", shWf);

            prompt.put("workflows", workflows);

            // ============================================================
            // 8. 待办计划系统（Todo Planner）
            // ============================================================
            JSONObject planSystem = new JSONObject();

            planSystem.put("when", "当用户任务包含 3+ 独立步骤，或系统注入了规划提示词时，在文本回复中附带计划 JSON");

            // 计划 JSON 格式（示例）
            JSONObject planFormat = new JSONObject();
            JSONArray planTasksExample = new JSONArray();
            JSONObject t1 = new JSONObject();
            t1.put("task_id", "T001");
            t1.put("content", "读取配置文件");
            t1.put("priority", 1);
            t1.put("deps", new JSONArray());
            t1.put("tool_needs", new JSONArray().put("file_read"));
            t1.put("checkpoint", "成功获取文件内容");
            planTasksExample.put(t1);
            JSONObject t2 = new JSONObject();
            t2.put("task_id", "T002");
            t2.put("content", "修改配置项 timeout=30");
            t2.put("priority", 1);
            t2.put("deps", new JSONArray().put("T001"));
            t2.put("tool_needs", new JSONArray().put("file_write"));
            t2.put("checkpoint", "写入成功");
            planTasksExample.put(t2);
            JSONObject t3 = new JSONObject();
            t3.put("task_id", "T003");
            t3.put("content", "运行 Python 验证");
            t3.put("priority", 2);
            t3.put("deps", new JSONArray().put("T002"));
            t3.put("tool_needs", new JSONArray().put("python"));
            planTasksExample.put(t3);
            planFormat.put("tasks", planTasksExample);
            planSystem.put("plan_format", planFormat);

            // 任务字段说明
            JSONObject taskFields = new JSONObject();
            taskFields.put("task_id", "任务唯一标识（字符串，如 T001、T002a）");
            taskFields.put("content", "任务简述，一句话描述");
            taskFields.put("priority", "优先级（整数）：1=最高，2=高，3=中，4=低，5=最低");
            taskFields.put("deps", "前置依赖任务 ID 列表（字符串数组），必须先完成才能开始");
            taskFields.put("tool_needs", "预计需要的工具名列表（字符串数组），帮助系统分配工作流");
            taskFields.put("checkpoint", "验收标准（可选），描述怎样算完成");
            taskFields.put("desc", "详细描述（可选）");
            planSystem.put("task_fields", taskFields);

            // 任务状态
            JSONObject taskStatuses = new JSONObject();
            taskStatuses.put("pending", "待处理，等待前置依赖完成");
            taskStatuses.put("in_progress", "正在执行中（同时只有一个）");
            taskStatuses.put("completed", "已完成");
            taskStatuses.put("failed", "失败（最多重试 3 次）");
            taskStatuses.put("paused", "暂停，等待手动恢复");
            planSystem.put("task_statuses", taskStatuses);

            // 使用方式（精简版）
            JSONArray planUsage = new JSONArray();
            planUsage.put("首次输出：在文本回复 result.content 中输出 {\"tasks\":[...]} 计划 JSON。系统解析后自动加载并推送第一个任务");
            planUsage.put("执行任务：收到 type=system action=execute_task 后，按 task 字段执行。完成后在 result 中加 plan_update 推进");
            planUsage.put("推进计划：工具执行后 result 中附带 plan 进度，你必须在回复中加 plan_update（action=complete_task/mark_failed/update_plan）");
            planUsage.put("完成总结：收到 action=plan_complete 后，用自然语言汇总结果回复用户");
            planSystem.put("usage", planUsage);

            prompt.put("plan_system", planSystem);

            // ============================================================
            // 9. 文件操作示例
            // ============================================================
            JSONArray fileOps = new JSONArray();
            fileOps.put(new JSONObject().put("scenario", "读取文件").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_read")
                                    .put("arguments", new JSONObject().put("path", "config.txt")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "读取指定行范围").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_read")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 10).put("end_line", 20)))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "替换第3行").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 3).put("content", "new_value=123").put("mode", "replace")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "在第5行前插入").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 5).put("content", "inserted_line").put("mode", "insert")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "追加到末尾").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("content", "appended_line").put("mode", "append")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "删除第3行").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 3).put("content", "").put("mode", "replace")))
                            .put("id", 1001)));
            prompt.put("file_ops", fileOps);

            // ============================================================
            // 10. Python 示例
            // ============================================================
            JSONArray pythonOps = new JSONArray();
            pythonOps.put(new JSONObject().put("scenario", "执行 Python 代码").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "python")
                                    .put("arguments", new JSONObject().put("script", "print(1 + 1)")))
                            .put("id", 1001)));
            pythonOps.put(new JSONObject().put("scenario", "执行多行 Python").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "python")
                                    .put("arguments", new JSONObject().put("script", "import os\nprint(os.listdir('/'))")))
                            .put("id", 1001)));
            prompt.put("python_ops", pythonOps);

            // ============================================================
            // 11. 工具列表
            // ============================================================
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

            // ============================================================
            // 12. GM 内存修改
            // ============================================================
            JSONArray gmFlow = new JSONArray();
            gmFlow.put("1. 检查 Root -> 调用 gm_root_status");
            gmFlow.put("2. 获取进程列表 -> 调用 gm_process_list，找到目标应用的 pid");
            gmFlow.put("3. 附加进程 -> 调用 gm_attach_process，传入 pid");
            gmFlow.put("4. 搜索数值 -> 调用 gm_memory_search，传入当前数值和数据类型");
            gmFlow.put("5. 读取/写入 -> 调用 gm_memory_read 或 gm_memory_write");
            gmFlow.put("6. 冻结（可选）-> 调用 gm_memory_freeze 锁定数值");
            prompt.put("gm_flow", gmFlow);

            JSONArray dataTypes = new JSONArray();
            dataTypes.put(new JSONObject().put("type", "byte").put("bytes", 1).put("use", "小数值 0~255"));
            dataTypes.put(new JSONObject().put("type", "word").put("bytes", 2).put("use", "中等数值 0~65535"));
            dataTypes.put(new JSONObject().put("type", "dword").put("bytes", 4).put("use", "最常用，金币/血量/等级等整数"));
            dataTypes.put(new JSONObject().put("type", "qword").put("bytes", 8).put("use", "64位大整数"));
            dataTypes.put(new JSONObject().put("type", "float").put("bytes", 4).put("use", "小数，坐标/速度/角度等"));
            dataTypes.put(new JSONObject().put("type", "double").put("bytes", 8).put("use", "高精度小数"));
            prompt.put("data_types", dataTypes);

            JSONArray luaApi = new JSONArray();
            luaApi.put("gg.searchNumber(值, gg.TYPE_DWORD) 搜索数值");
            luaApi.put("gg.getResults(count) 获取搜索结果");
            luaApi.put("gg.editAll(新值, gg.TYPE_DWORD) 修改所有结果");
            luaApi.put("gg.toast(消息) 显示提示");
            prompt.put("lua_api", luaApi);

            // ============================================================
            // 13. 调用示例
            // ============================================================
            JSONArray examples = new JSONArray();
            examples.put(new JSONObject().put("step", "检查Root").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "gm_root_status")
                                    .put("arguments", new JSONObject()))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "获取进程列表").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "gm_process_list")
                                    .put("arguments", new JSONObject()))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "附加进程 pid=1234").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "gm_attach_process")
                                    .put("arguments", new JSONObject().put("pid", 1234)))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "搜索金币值5000").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "gm_memory_search")
                                    .put("arguments", new JSONObject().put("value", "5000").put("type", "dword")))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "写入新值99999到地址0x12345678").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "gm_memory_write")
                                    .put("arguments", new JSONObject().put("address", "0x12345678").put("value", "99999").put("type", "dword")))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "读取文件").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_read")
                                    .put("arguments", new JSONObject().put("path", "/sdcard/Download/config.txt")))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "替换文件第3行").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "/sdcard/Download/config.txt").put("line", 3).put("content", "new_value=42").put("mode", "replace")))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "在文件末尾追加").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "/sdcard/Download/log.txt").put("content", "2026-07-03 事件记录").put("mode", "append")))
                            .put("id", 1001)));
            examples.put(new JSONObject().put("step", "直接回答用户").put("call",
                    new JSONObject().put("jsonrpc", "2.0")
                            .put("result", new JSONObject().put("type", "reply").put("content", "我来帮你解决这个问题..."))
                            .put("id", 1001)));
            prompt.put("examples", examples);

            // ============================================================
            // 14. 工具结果 / 初始化确认
            // ============================================================
            JSONObject resultFmt = new JSONObject();
            resultFmt.put("success", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", new JSONObject().put("content",
                            new JSONArray().put(new JSONObject().put("type", "text").put("text", "工具返回结果文本"))))
                    .put("plan", new JSONObject().put("total", 5).put("completed", 1).put("failed", 0).put("summary", "已完成 1/5"))
                    .put("id", "对应工具调用的 id"));
            resultFmt.put("note", "result 外可能附带 plan 字段（total/completed/failed/summary/active_task），有则说明有待办计划，需加 plan_update 推进");
            resultFmt.put("error", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("error", new JSONObject().put("code", -32603).put("message", "错误说明"))
                    .put("id", "对应工具调用的 id"));
            prompt.put("tool_result_format", resultFmt);

            prompt.put("init_reply",
                    new JSONObject().put("jsonrpc", "2.0")
                            .put("result", new JSONObject().put("type", "reply").put("content", "已接收工具协议，可以开始任务"))
                            .put("id", 1001));

            return prompt.toString();

        } catch (JSONException e) {
            e.printStackTrace();
            return "{}";
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
                return ApkMcpClient.getInstance().callTool(name, arguments);
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
