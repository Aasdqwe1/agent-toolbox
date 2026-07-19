package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * create_plan 工具 —— 以标准 MCP tools/call 形式创建待办计划。
 *
 * 这是“计划模式”的标准入口：LLM 不再把 {"tasks":[...]} 作为转义 JSON 字符串
 * 塞进 result.content（那会导致外层 JSON-RPC 信封非法、需服务端校准），
 * 而是直接调用本工具，参数 arguments.tasks 即为结构化的任务数组。
 *
 * 注意：在对话主循环中，McpServer 会拦截 create_plan 并加载计划到会话 PlanState，
 * 不会真正走到 execute()；execute() 仅作为无会话上下文时的兜底提示。
 */
public class PlanCreateTool implements Tool {

    @Override
    public String getName() {
        return "create_plan";
    }

    @Override
    public String getDescription() {
        return "创建待办计划（标准工具调用形式）。当用户任务包含 3 个以上步骤、需要依次完成时调用。"
                + "参数 tasks 为任务数组，每个任务含 task_id(如\"T001\")、content(任务描述)、"
                + "可选 deps(依赖的任务ID)、tool_needs(所需工具名)、priority(1-5)、checkpoint(验收标准)。"
                + "调用后系统会自动加载计划并下发第一个任务，你按 execute_task 指令执行并在完成时用 plan_update 推进。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject tasks = new JSONObject();
            tasks.put("type", "array");
            tasks.put("description", "任务数组，按执行顺序排列");
            JSONObject taskItem = new JSONObject();
            taskItem.put("type", "object");
            JSONObject tp = new JSONObject();

            JSONObject taskId = new JSONObject();
            taskId.put("type", "string");
            taskId.put("description", "任务唯一标识，如 \"T001\"、\"T002\"");
            tp.put("task_id", taskId);

            JSONObject content = new JSONObject();
            content.put("type", "string");
            content.put("description", "一句话描述任务");
            tp.put("content", content);

            JSONObject deps = new JSONObject();
            deps.put("type", "array");
            deps.put("description", "依赖的前置任务 ID 列表（可选）");
            JSONObject depItem = new JSONObject();
            depItem.put("type", "string");
            deps.put("items", depItem);
            tp.put("deps", deps);

            JSONObject toolNeeds = new JSONObject();
            toolNeeds.put("type", "array");
            toolNeeds.put("description", "预计使用的工具名列表，如 [\"file_read\",\"python\"]（可选）");
            JSONObject tnItem = new JSONObject();
            tnItem.put("type", "string");
            toolNeeds.put("items", tnItem);
            tp.put("tool_needs", toolNeeds);

            JSONObject priority = new JSONObject();
            priority.put("type", "integer");
            priority.put("description", "优先级 1-5，默认 3（可选）");
            tp.put("priority", priority);

            JSONObject checkpoint = new JSONObject();
            checkpoint.put("type", "string");
            checkpoint.put("description", "验收标准（可选）");
            tp.put("checkpoint", checkpoint);

            taskItem.put("properties", tp);
            JSONArray taskRequired = new JSONArray();
            taskRequired.put("task_id");
            taskRequired.put("content");
            taskItem.put("required", taskRequired);
            tasks.put("items", taskItem);
            properties.put("tasks", tasks);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("tasks");
            schema.put("required", required);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        // 对话主循环中由 McpServer 拦截并真正加载计划；此处仅在无会话上下文时兜底。
        if (arguments != null && arguments.has("tasks")) {
            return "计划已接收（参数 tasks 含 " + arguments.optJSONArray("tasks").length() + " 个任务）。"
                    + "若在对话中使用，系统会自动加载计划并下发首个任务。";
        }
        return "create_plan 工具：请在 arguments.tasks 中提供任务数组，"
                + "形如 {\"tasks\":[{\"task_id\":\"T001\",\"content\":\"描述\",\"tool_needs\":[\"file_read\"]}]}";
    }
}
