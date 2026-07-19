package com.example.agenttoolbox.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import com.example.agenttoolbox.AppLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务管理器 — 待办核心管理层
 * 
 * 职责：
 *   - 待办 CRUD、状态机流转
 *   - 依赖解析、冲突校验
 *   - 失败重试、重规划触发器
 *   - 进度摘要生成
 */
public class TaskManager {

    private static TaskManager instance;

    public static synchronized TaskManager getInstance() {
        if (instance == null) {
            instance = new TaskManager();
        }
        return instance;
    }

    private void log(String msg) {
        AppLogger.d("TaskManager", msg);
    }

    /**
     * 从 LLM 生成的 JSON 解析并加载任务计划
     * 
     * @param planState 计划状态对象
     * @param planJson  LLM 生成的计划 JSON
     * @return 解析后的任务列表
     */
    public List<Task> loadPlan(PlanState planState, JSONObject planJson) {
        planState.saveSnapshot();
        planState.tasks.clear();
        planState.confirmed = false;
        planState.createTime = java.time.LocalDateTime.now().toString().replace("T", " ");

        JSONArray tasksArr = planJson.optJSONArray("tasks");
        if (tasksArr == null) {
            // 兼容：LLM 可能直接返回 JSONObject 包含 tasks 数组，或本身就是数组
            // 如果 planJson 本身是数组类型，直接使用
            return new ArrayList<>();
        }

        // 归一化：保证每个任务都有“非空且唯一”的 task_id 与“非空”的 content，
        // 否则下游 execute_task 会下发空字段（任务工具不严谨问题）。
        Set<String> usedIds = new HashSet<>();
        int autoSeq = 0;
        for (int i = 0; i < tasksArr.length(); i++) {
            JSONObject taskJson = tasksArr.optJSONObject(i);
            if (taskJson == null) continue;
            Task task = Task.fromJson(taskJson);

            // 1) task_id 缺失/空白 → 自动分配 T001、T002…，并规避与已有 id 重复
            if (task.taskId == null || task.taskId.trim().isEmpty()) {
                String auto;
                do { auto = String.format("T%03d", ++autoSeq); } while (usedIds.contains(auto));
                task.taskId = auto;
                log("[PLAN] 任务缺少 task_id，已自动分配: " + auto);
            }
            usedIds.add(task.taskId);

            // 2) content 缺失/空白 → 回退填充，保证 execute_task 不下发空内容
            if (task.content == null || task.content.trim().isEmpty()) {
                task.content = "任务 " + task.taskId;
                log("[PLAN] 任务 " + task.taskId + " 缺少 content，已回退填充");
            }

            planState.tasks.add(task);
        }

        return new ArrayList<>(planState.tasks);
    }

    /**
     * 确认计划（用户确认后）
     */
    public void confirmPlan(PlanState planState) {
        planState.confirmed = true;
        planState.resetRoundCount();
    }

    /**
     * 选取下一个可执行任务（依赖已解决、pending、按优先级）
     */
    public Task selectNextTask(PlanState planState) {
        if (planState.activeTask != null) {
            // 当前有进行中任务，检查是否完成
            if (planState.activeTask.status == Task.Status.COMPLETED
                || planState.activeTask.status == Task.Status.FAILED
                || planState.activeTask.status == Task.Status.SKIPPED) {
                planState.activeTask = null;
            } else {
                return planState.activeTask; // 继续当前任务
            }
        }

        Task next = planState.getNextTask();
        if (next != null) {
            next.markInProgress();
            planState.activeTask = next;
        }
        return next;
    }

    /**
     * 标记当前任务完成
     */
    public void markCurrentDone(PlanState planState) {
        if (planState.activeTask != null) {
            planState.activeTask.markCompleted();
            planState.activeTask = null;
            planState.resetRoundCount();
        }
    }

    /**
     * 标记当前任务失败
     */
    public void markCurrentFailed(PlanState planState, String reason) {
        if (planState.activeTask != null) {
            planState.activeTask.markFailed(reason);
            if (planState.activeTask.canRetry()) {
                planState.activeTask.resetToPending();
            }
            planState.activeTask = null;
            planState.resetRoundCount();
        }
    }

    /**
     * 标记指定 ID 的任务跳过（不重试、不计入失败）
     */
    public void markTaskSkipped(PlanState planState, String taskId, String reason) {
        for (Task t : planState.tasks) {
            if (t.taskId.equals(taskId)) {
                t.markSkipped(reason);
                if (planState.activeTask != null && planState.activeTask.taskId.equals(taskId)) {
                    planState.activeTask = null;
                }
                planState.resetRoundCount();
                break;
            }
        }
    }

    /**
     * 标记当前活跃任务跳过
     */
    public void markCurrentSkipped(PlanState planState, String reason) {
        if (planState.activeTask != null) {
            planState.activeTask.markSkipped(reason);
            planState.activeTask = null;
            planState.resetRoundCount();
        }
    }

    /**
     * 标记指定 ID 的任务完成
     */
    public void markTaskDone(PlanState planState, String taskId) {
        for (Task t : planState.tasks) {
            if (t.taskId.equals(taskId)) {
                t.markCompleted();
                if (planState.activeTask != null && planState.activeTask.taskId.equals(taskId)) {
                    planState.activeTask = null;
                }
                planState.resetRoundCount();
                break;
            }
        }
    }

    /**
     * 标记指定 ID 的任务失败
     */
    public void markTaskFailed(PlanState planState, String taskId, String reason) {
        for (Task t : planState.tasks) {
            if (t.taskId.equals(taskId)) {
                t.markFailed(reason);
                if (t.canRetry()) t.resetToPending();
                if (planState.activeTask == t) planState.activeTask = null;
                planState.resetRoundCount();
                break;
            }
        }
    }

    /**
     * 触发重规划：保留已完成任务，重置未完成任务
     */
    public JSONObject buildReplanContext(PlanState planState) {
        JSONObject ctx = new JSONObject();
        try {
            // 已完成任务
            JSONArray completed = new JSONArray();
            for (Task t : planState.tasks) {
                if (t.status == Task.Status.COMPLETED) {
                    completed.put(t.toJson());
                }
            }
            ctx.put("completed_tasks", completed);

            // 失败任务
            JSONArray failed = new JSONArray();
            for (Task t : planState.tasks) {
                if (t.status == Task.Status.FAILED) {
                    failed.put(t.toJson());
                }
            }
            ctx.put("failed_tasks", failed);

        // 未完成任务
        JSONArray pending = new JSONArray();
        for (Task t : planState.tasks) {
            if (t.status == Task.Status.PENDING || t.status == Task.Status.IN_PROGRESS) {
                pending.put(t.toJson());
            }
        }
        ctx.put("pending_tasks", pending);

        // 跳过任务（供重规划参考，但不触发重试）
        JSONArray skipped = new JSONArray();
        for (Task t : planState.tasks) {
            if (t.status == Task.Status.SKIPPED) {
                skipped.put(t.toJson());
            }
        }
        ctx.put("skipped_tasks", skipped);

            ctx.put("replan_reason", "部分任务失败需重规划");
        } catch (Exception e) {}
        return ctx;
    }

    /**
     * 检查是否需要生成计划
     * 条件：任务≥3步工具调用、涉及文件修改、存在截止时间等
     */
    public boolean shouldGeneratePlan(String userMessage, int toolCallCount) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        // 多步骤任务
        boolean multiStep = msg.contains("步骤") || msg.contains("然后") || msg.contains("接着")
            || msg.contains("再") || msg.contains("之后") || msg.contains("先")
            || msg.contains("第1") || msg.contains("第一") || msg.contains("1.");
        // 文件修改任务
        boolean fileModify = msg.contains("修改") || msg.contains("编辑") || msg.contains("替换")
            || msg.contains("追加") || msg.contains("写入") || msg.contains("保存");
        // 截止时间
        boolean hasDeadline = msg.contains("截止") || msg.contains("之前") || msg.contains("期限")
            || msg.contains("ddl") || msg.contains("deadline");
        // 多子目标
        boolean multiGoal = msg.contains("同时") || msg.contains("并且") || msg.contains("另外")
            || msg.contains("还要") || msg.contains("以及");

        return multiStep || fileModify || hasDeadline || multiGoal || toolCallCount >= 3;
    }

    /**
     * 生成 Plan 提示词模板（注入到 LLM context）
     */
    public String generatePlanPrompt(String userMessage) {
        return "你是任务规划智能体，收到目标后必须输出结构化待办JSON，遵守规则：\n"
            + "1. 拆解为3-10个可原子执行步骤，每个步骤绑定对应工具\n"
            + "2. 标注任务ID、前置依赖、优先级(1紧急~5低优)、预估耗时\n"
            + "3. 识别并行任务（无依赖可同步执行）、串行任务\n"
            + "4. 标记风险检查点，执行失败后可单独重试单条任务\n"
            + "5. 禁止遗漏需求，全部完成后统一汇总输出结果\n"
            + "6. 非必需或无法执行的步骤（如可选步骤、依赖不存在、用户取消）不要强行执行，执行阶段可用 plan_update action=skip_task 回报跳过（带 reason），下游依赖仍会继续\n\n"
            + "输出格式（仅返回JSON，无多余文字）：\n"
            + "{\n"
            + "  \"jsonrpc\": \"2.0\",\n"
            + "  \"result\": {\n"
            + "    \"type\": \"reply\",\n"
            + "    \"content\": \"{\\n"
            + "  \\\"tasks\\\": [\\n"
            + "    {\\\"task_id\\\":\\\"T001\\\",\\\"content\\\":\\\"步骤描述\\\",\\\"priority\\\":1,\\\"deps\\\":[],\\\"tool_needs\\\":[\\\"file_read\\\"],\\\"checkpoint\\\":\\\"验收标准\\\"},\\n"
            + "    {\\\"task_id\\\":\\\"T002\\\",\\\"content\\\":\\\"下一步\\\",\\\"priority\\\":2,\\\"deps\\\":[\\\"T001\\\"],\\\"tool_needs\\\":[\\\"file_write\\\"]}\\n"
            + "  ]\\n"
            + "}\"\n"
            + "  },\n"
            + "  \"id\": 1001\n"
            + "}\n\n"
            + "用户目标: " + userMessage;
    }

    /**
     * 生成任务完成总结文本
     */
    public String generateSummary(PlanState planState) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 任务执行总结\n");
        sb.append("总任务: ").append(planState.totalTasks()).append(" 个\n");
        sb.append("已完成: ").append(planState.completedTasks()).append(" 个\n");
        sb.append("失败: ").append(planState.failedTasks()).append(" 个\n");
        sb.append("跳过: ").append(planState.skippedTasks()).append(" 个\n\n");

        sb.append("详细清单:\n");
        for (Task t : planState.tasks) {
            String icon = t.status == Task.Status.COMPLETED ? "[x]" :
                t.status == Task.Status.FAILED ? "[!]" :
                t.status == Task.Status.SKIPPED ? "[s]" : "[ ]";
            sb.append(icon).append(" ").append(t.taskId).append(": ").append(t.content);
            if (t.failReason != null) {
                sb.append(" (").append(t.failReason).append(")");
            }
            if (t.status == Task.Status.SKIPPED && t.skipReason != null) {
                sb.append(" (跳过: ").append(t.skipReason).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}