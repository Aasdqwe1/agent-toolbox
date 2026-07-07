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
     * 格式：纯 JSON 协议
     */
    public String getSystemPrompt() {
        try {
            JSONArray toolsArray = getToolsList();

            JSONObject prompt = new JSONObject();

            // 协议版本（JSON-RPC 2.0）
            prompt.put("protocol", "JSON-RPC 2.0");

            // 角色与强制规则
            prompt.put("role", "你是运行在 Android 设备上的 AI 助手，通过 MCP 工具箱为用户提供服务");
            prompt.put("enforce", "禁止输出任何自然语言、markdown、代码块标记或解释。所有回复必须是符合 JSON-RPC 2.0 规范的单个纯 JSON 对象，不得包含任何其他字符。");

            // 回复格式（JSON-RPC 2.0）
            // 注意：所有示例 id 都用 1001，表示"同一次对话里所有消息 id 相同，回带 initialize 的 id"
            JSONArray formats = new JSONArray();
            JSONObject fmt1 = new JSONObject();
            fmt1.put("type", "reply");
            fmt1.put("desc", "文本回答：以 result 返回，type=reply，content 放回答内容。id 必须是 initialize 请求里的 id（示例里是 1001）");
            fmt1.put("example", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", new JSONObject().put("type", "reply").put("content", "你的回答内容"))
                    .put("id", 1001));
            formats.put(fmt1);

            JSONObject fmt2 = new JSONObject();
            fmt2.put("type", "tool_call");
            fmt2.put("desc", "调用工具：method=tools/call，params 里放 name 和 arguments。id 必须是 initialize 请求里的 id（示例里是 1001），不要自创其他数字");
            fmt2.put("example", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tools/call")
                    .put("params", new JSONObject().put("name", "工具名")
                            .put("arguments", new JSONObject().put("参数名", "参数值")))
                    .put("id", 1001));
            formats.put(fmt2);

            JSONObject fmt3 = new JSONObject();
            fmt3.put("type", "system");
            fmt3.put("desc", "系统指令：服务端在后续轮次中发送的指令或计划推进消息，格式为 result 对象含 type=system 和 content 字段。你收到后按指令执行，回复格式依然遵循 reply/tool_call 规则");
            fmt3.put("example", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", new JSONObject().put("type", "system").put("content", "系统指令内容"))
                    .put("id", 1001));
            formats.put(fmt3);

            prompt.put("reply_formats", formats);

            // 核心规则
            JSONArray rules = new JSONArray();
            rules.put("每个回复都必须包含 jsonrpc=\"2.0\" 字段");
            rules.put("计划 JSON（{\"tasks\":[...]}）必须放在文本回复的 result.content 字符串内部，不得放在 content 外部或作为独立 JSON 输出");
            rules.put("id 规则：你收到的 initialize 请求里的 id 是本次对话的会话 ID，你后续的所有回复（result/error/tools/call）都必须回带这个 id，服务端工具结果也会用相同 id 响应");
            rules.put("文本回答：使用 result 对象，含 type=reply 和 content 字段，并带回 id");
            rules.put("工具调用：使用 method=\"tools/call\"，参数放在 params.name 和 params.arguments，并带 id");
            rules.put("每次只能调用一个工具。工具执行后会以 JSON-RPC 响应返回结果（相同 id），你再决定下一步");
            rules.put("若收到 error 对象，说明原因并修正参数重试");
            rules.put("GM 工具必须按流程顺序使用：检查Root -> 进程列表 -> 附加进程 -> 搜索 -> 读写");
            rules.put("地址格式：十六进制，如 0x7FFF1234");
            rules.put("文件写入路径限制：仅限 /sdcard/Download/、/sdcard/Documents/ 等安全目录");
            rules.put("文件读写最佳实践：先用 file_read 读取内容（带行号），再用 file_write 精确操作。避免手动计算行号");
            rules.put("file_write 的三种模式：replace=替换指定行（默认），insert=在指定行前插入，append=追加到末尾。优先用 insert/append 避免行号偏移");
            rules.put("多步编辑时：每次写入后行号会变化。如果连续修改多处，优先用 insert/append，或从文件末尾往前改（避免行号漂移）");
            rules.put("待办计划推进规则：当有待办计划时，每执行完一个工具后，你必须在文本回复的 result 中添加 plan_update 字段来推进计划。例如：{\"action\":\"complete_task\",\"task_id\":\"T001\"}。系统不会自动推进计划，完全由你控制");
            rules.put("file_write 的 content 参数会自动剥离行号前缀，可以直接把 file_read 的输出当 content 传入");
            rules.put("Python 工具已内嵌 Python 3.14 环境，直接调用 python 工具即可执行代码，无需通过 shell 检查 Python 是否可用");
            rules.put("执行 Python 代码时直接使用 python 工具，不要用 shell which python 或 shell python3 等方式");
            rules.put("JSON 字符串值内的双引号必须转义为 \\\"（反斜杠加引号），否则 JSON 解析失败。Python 代码中优先使用单引号避免冲突");
            prompt.put("rules", rules);

            // ============================================================
            // 工作流状态机（FSM）
            // 服务端内置三套硬编码状态机，自动引导工具调用流程。
            // 当系统检测到你的任务类型后，会自动切换对应工作流，
            // 并通过 FSM 校验确保工具调用顺序正确。
            // ============================================================
            JSONObject workflows = new JSONObject();

            // 文件读写工作流
            JSONObject fileWf = new JSONObject();
            fileWf.put("trigger", "用户要求读取、修改、创建文件时自动激活");
            JSONArray fileStates = new JSONArray();
            fileStates.put("IDLE → 空闲");
            fileStates.put("NEED_READ → 系统自动触发 file_read，读取目标文件");
            fileStates.put("READ_SUCCESS → 文件内容已缓存，你收到文件内容后分析并决定如何修改");
            fileStates.put("NEED_EDIT → 你需要输出修改后的新内容（仅输出内容，不要调用 file_write）");
            fileStates.put("WRITE_READY → 系统自动触发 file_write 写入");
            fileStates.put("WRITE_DONE → 写入完成，流程结束");
            fileWf.put("states", fileStates);
            fileWf.put("note", "READ_SUCCESS 阶段你会收到带行号的文件内容。NEED_EDIT 阶段你只需输出修改后的完整文本，系统会自动写入。路径限白名单：/sdcard/Download/、/sdcard/Documents/ 等");
            workflows.put("file", fileWf);

            // Python 工作流
            JSONObject pyWf = new JSONObject();
            pyWf.put("trigger", "用户要求执行 Python 代码时自动激活");
            JSONArray pyStates = new JSONArray();
            pyStates.put("IDLE → 空闲");
            pyStates.put("NEED_GEN_SCRIPT → 你需要生成 Python 脚本代码");
            pyStates.put("RUN_SCRIPT → 系统自动执行 python 工具");
            pyStates.put("EXEC_SUCCESS → 执行成功，收到结果");
            pyStates.put("EXEC_ERROR → 执行失败，需要修正代码重试");
            pyWf.put("states", pyStates);
            pyWf.put("note", "Python 3.14 已内嵌，直接调用 python 工具。禁止使用 os.system/subprocess/import ctypes 等危险调用。超时 60 秒。优先使用单引号避免 JSON 转义冲突");
            workflows.put("python", pyWf);

            // Shell 工作流
            JSONObject shWf = new JSONObject();
            shWf.put("trigger", "用户要求执行 Shell 命令时自动激活");
            JSONArray shStates = new JSONArray();
            shStates.put("IDLE → 空闲");
            shStates.put("NEED_PARSE_CMD → 你需要从用户意图中提取/生成 Shell 命令");
            shStates.put("RUN_CMD → 系统自动执行 shell 工具");
            shStates.put("CMD_SUCCESS → 执行成功，收到输出");
            shStates.put("CMD_ERROR → 执行失败，检查命令修正");
            shWf.put("states", shStates);
            shWf.put("note", "禁止高危指令：rm -rf /、su、mount、mkfs、dd if=/dev/zero 等破坏性操作。超时 30 秒");
            workflows.put("shell", shWf);

            prompt.put("workflows", workflows);

            // ============================================================
            // 待办计划系统（Todo Planner）
            // 对于复杂多步骤任务，系统会自动触发计划生成。
            // 你需要在回复中输出任务计划 JSON，系统会自动解析并逐个执行。
            // ============================================================
            JSONObject planSystem = new JSONObject();

            planSystem.put("when", "当用户的任务包含 3 个以上独立步骤，或系统在消息中注入了规划提示词时，你应该在回复中附带计划 JSON");

            // 计划 JSON 格式
            JSONObject planFormat = new JSONObject();
            JSONArray planTasksExample = new JSONArray();
            JSONObject t1 = new JSONObject();
            t1.put("task_id", "1");
            t1.put("content", "读取配置文件");
            t1.put("priority", "high");
            t1.put("deps", new JSONArray());
            t1.put("tool_needs", new JSONArray().put("file_read"));
            planTasksExample.put(t1);
            JSONObject t2 = new JSONObject();
            t2.put("task_id", "2");
            t2.put("content", "修改配置项 timeout=30");
            t2.put("priority", "high");
            t2.put("deps", new JSONArray().put("1"));
            t2.put("tool_needs", new JSONArray().put("file_write"));
            planTasksExample.put(t2);
            JSONObject t3 = new JSONObject();
            t3.put("task_id", "3");
            t3.put("content", "运行 Python 验证脚本");
            t3.put("priority", "medium");
            t3.put("deps", new JSONArray().put("2"));
            t3.put("tool_needs", new JSONArray().put("python"));
            planTasksExample.put(t3);
            planFormat.put("tasks", planTasksExample);
            planSystem.put("plan_format", planFormat);

            // 任务字段说明
            JSONObject taskFields = new JSONObject();
            taskFields.put("task_id", "任务唯一标识（字符串，如 \"1\"、\"2a\"）");
            taskFields.put("content", "任务简述，一句话描述要做什么");
            taskFields.put("priority", "优先级：high/medium/low");
            taskFields.put("deps", "前置依赖任务 ID 列表，必须先完成依赖才能开始本任务");
            taskFields.put("tool_needs", "预计需要的工具名列表（可选，帮助系统分配工作流）");
            taskFields.put("checkpoint", "验收标准（可选），描述怎样算完成");
            taskFields.put("desc", "详细描述（可选），补充说明");
            planSystem.put("task_fields", taskFields);

            // 任务状态
            JSONObject taskStatuses = new JSONObject();
            taskStatuses.put("pending", "待处理，等待前置依赖完成");
            taskStatuses.put("in_progress", "正在执行中（同时只有一个）");
            taskStatuses.put("completed", "已完成");
            taskStatuses.put("failed", "失败（最多重试 3 次）");
            taskStatuses.put("paused", "暂停，等待手动恢复");
            planSystem.put("task_statuses", taskStatuses);

            // 使用方式
            JSONArray planUsage = new JSONArray();
            planUsage.put("首次输出计划：严格按照 reply_formats 中定义的文本回复格式，将 {\"tasks\":[...]} 完整计划 JSON 字符串作为 content 字段的值输出。系统检测到计划后会自动加载并选取第一个任务发给你");
            planUsage.put("推进计划：工具执行后你会收到工具结果和当前计划状态。此时你需要在文本回复的 result 对象中添加 plan_update 字段来告诉系统如何推进计划，不能由系统自动推进");
            planUsage.put("plan_update 是 result 对象的一个附加字段，格式如下：");
            planUsage.put("{\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"reply\",\"content\":\"你的回复\",\"plan_update\":{\"action\":\"操作类型\",\"task_id\":\"任务ID\"}},\"id\":1001}");
            planUsage.put("支持的操作类型：");
            planUsage.put("  - complete_task: 标记指定任务完成。示例：{\"action\":\"complete_task\",\"task_id\":\"T001\"}");
            planUsage.put("  - mark_failed: 标记任务失败。示例：{\"action\":\"mark_failed\",\"task_id\":\"T001\",\"reason\":\"文件不存在\"}");
            planUsage.put("  - update_plan: 更新整个计划。示例：{\"action\":\"update_plan\",\"plan\":{\"tasks\":[...]}}");
            planUsage.put("完整示例流程：");
            planUsage.put("  步骤1（工具调用）：调用 file_read 读取文件");
            planUsage.put("  步骤2（收到结果）：文件内容已返回");
            planUsage.put("  步骤3（推进计划）：{\"jsonrpc\":\"2.0\",\"result\":{\"type\":\"reply\",\"content\":\"文件已读取\",\"plan_update\":{\"action\":\"complete_task\",\"task_id\":\"T001\"}},\"id\":1001}");
            planUsage.put("  步骤4（系统回复）：下一步任务: [T002] 修改文件。请调用对应工具执行。");
            planUsage.put("全部任务完成后：系统会自动生成总结并结束对话");
            planSystem.put("usage", planUsage);

            prompt.put("plan_system", planSystem);

            // 文件操作最佳实践
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
            fileOps.put(new JSONObject().put("scenario", "替换第3行为新内容").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 3).put("content", "new_value=123").put("mode", "replace")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "在第5行前插入新行").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 5).put("content", "inserted_line").put("mode", "insert")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "追加内容到文件末尾").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("content", "appended_line").put("mode", "append")))
                            .put("id", 1001)));
            fileOps.put(new JSONObject().put("scenario", "删除第3行（content为空）").put("call",
                    new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
                            .put("params", new JSONObject().put("name", "file_write")
                                    .put("arguments", new JSONObject().put("path", "config.txt").put("line", 3).put("content", "").put("mode", "replace")))
                            .put("id", 1001)));
            prompt.put("file_ops", fileOps);

            // Python 使用示例
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

            // 工具列表
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

            // GM 内存修改流程
            JSONArray gmFlow = new JSONArray();
            gmFlow.put("1. 检查 Root -> 调用 gm_root_status");
            gmFlow.put("2. 获取进程列表 -> 调用 gm_process_list，找到目标应用的 pid");
            gmFlow.put("3. 附加进程 -> 调用 gm_attach_process，传入 pid");
            gmFlow.put("4. 搜索数值 -> 调用 gm_memory_search，传入当前数值和数据类型");
            gmFlow.put("5. 读取/写入 -> 调用 gm_memory_read 或 gm_memory_write");
            gmFlow.put("6. 冻结（可选）-> 调用 gm_memory_freeze 锁定数值");
            prompt.put("gm_flow", gmFlow);

            // 数据类型
            JSONArray dataTypes = new JSONArray();
            dataTypes.put(new JSONObject().put("type", "byte").put("bytes", 1).put("use", "小数值 0~255"));
            dataTypes.put(new JSONObject().put("type", "word").put("bytes", 2).put("use", "中等数值 0~65535"));
            dataTypes.put(new JSONObject().put("type", "dword").put("bytes", 4).put("use", "最常用，金币/血量/等级等整数"));
            dataTypes.put(new JSONObject().put("type", "qword").put("bytes", 8).put("use", "64位大整数"));
            dataTypes.put(new JSONObject().put("type", "float").put("bytes", 4).put("use", "小数，坐标/速度/角度等"));
            dataTypes.put(new JSONObject().put("type", "double").put("bytes", 8).put("use", "高精度小数"));
            prompt.put("data_types", dataTypes);

            // Lua 常用函数
            JSONArray luaApi = new JSONArray();
            luaApi.put("gg.searchNumber(值, gg.TYPE_DWORD) 搜索数值");
            luaApi.put("gg.getResults(count) 获取搜索结果");
            luaApi.put("gg.editAll(新值, gg.TYPE_DWORD) 修改所有结果");
            luaApi.put("gg.toast(消息) 显示提示");
            prompt.put("lua_api", luaApi);

            // 调用示例（JSON-RPC 2.0）
            // 注意：所有示例的 id 都用同一个值 1001，表示"同一次对话里所有消息 id 相同"
            // LLM 应该回带 initialize 请求里的 id，而不是自创递增 id
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

            // 工具结果格式（服务端返回给 LLM 的 JSON-RPC 2.0 响应）
            JSONObject resultFmt = new JSONObject();
            resultFmt.put("success", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", new JSONObject().put("content",
                            new JSONArray().put(new JSONObject().put("type", "text").put("text", "工具返回结果文本"))))
                    .put("id", "对应工具调用的 id"));
            resultFmt.put("error", new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("error", new JSONObject().put("code", -32603).put("message", "错误说明"))
                    .put("id", "对应工具调用的 id"));
            prompt.put("tool_result_format", resultFmt);

            // 初始化确认（JSON-RPC 2.0）—— id 与 examples 保持一致，强调"回带 initialize 的 id"
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
