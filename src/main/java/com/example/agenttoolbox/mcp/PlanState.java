package com.example.agenttoolbox.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局计划状态 — 会话内持久化任务系统
 * 
 * 核心字段：
 *   - tasks: 全部待办数组
 *   - activeTask: 当前唯一进行中任务（限制单步执行防混乱）
 *   - roundsSinceUpdate: 未更新计划轮次，超限自动提醒刷新
 *   - historyPlans: 历史版本，支持回滚重规划
 */
public class PlanState {

    /** 全部待办任务 */
    public final List<Task> tasks = new CopyOnWriteArrayList<>();

    /** 当前唯一进行中任务 */
    public Task activeTask;

    /** 未更新计划轮次，超限自动提醒刷新 */
    public int roundsSinceUpdate = 0;

    /** 每N轮强制刷新待办清单 */
    public static final int REFRESH_INTERVAL = 3;

    /** 历史版本（JSON 快照），支持回滚 */
    public final List<String> historyPlans = new ArrayList<>();

    /** 计划是否已确认 */
    public boolean confirmed = false;

    /** 计划创建时间 */
    public String createTime;

    /** 总任务数 */
    public int totalTasks() { return tasks.size(); }

    /** 已完成任务数 */
    public int completedTasks() {
        int count = 0;
        for (Task t : tasks) if (t.status == Task.Status.COMPLETED) count++;
        return count;
    }

    /** 失败任务数 */
    public int failedTasks() {
        int count = 0;
        for (Task t : tasks) if (t.status == Task.Status.FAILED) count++;
        return count;
    }

    /** 进行中任务数（应 <= 1） */
    public int inProgressTasks() {
        int count = 0;
        for (Task t : tasks) if (t.status == Task.Status.IN_PROGRESS) count++;
        return count;
    }

    /** 是否全部完成 */
    public boolean allCompleted() {
        if (tasks.isEmpty()) return false;
        return completedTasks() == tasks.size();
    }

    /** 是否需要刷新计划 */
    public boolean needsRefresh() {
        return roundsSinceUpdate >= REFRESH_INTERVAL;
    }

    /** 递增轮次计数 */
    public void incrementRound() {
        roundsSinceUpdate++;
    }

    /** 重置轮次计数 */
    public void resetRoundCount() {
        roundsSinceUpdate = 0;
    }

    /** 保存当前计划快照到历史 */
    public void saveSnapshot() {
        historyPlans.add(toJson().toString());
    }

    /** 获取待办摘要文本 */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("任务进度: ").append(completedTasks()).append("/").append(totalTasks());
        sb.append(" 完成, ").append(failedTasks()).append(" 失败");
        if (activeTask != null) {
            sb.append(" | 当前: ").append(activeTask.content);
        }
        return sb.toString();
    }

    /** 获取下一个可执行任务（依赖已解决、pending状态、按优先级排序） */
    public Task getNextTask() {
        // 先找 pending 且依赖已解决的任务
        List<Task> candidates = new ArrayList<>();
        for (Task t : tasks) {
            if (t.status == Task.Status.PENDING && t.depsResolved(tasks)) {
                candidates.add(t);
            }
        }
        if (candidates.isEmpty()) return null;

        // 按优先级排序（数字越小越紧急），相同优先级按截止时间
        java.util.Collections.sort(candidates, new java.util.Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                if (a.priority != b.priority) return Integer.compare(a.priority, b.priority);
                if (a.deadline == null && b.deadline == null) return 0;
                if (a.deadline == null) return 1;
                if (b.deadline == null) return -1;
                return a.deadline.compareTo(b.deadline);
            }
        });
        return candidates.get(0);
    }

    /** 序列化为 JSON */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            JSONArray taskArr = new JSONArray();
            for (Task t : tasks) taskArr.put(t.toJson());
            json.put("tasks", taskArr);
            if (activeTask != null) json.put("active_task", activeTask.toJson());
            json.put("rounds_since_update", roundsSinceUpdate);
            json.put("confirmed", confirmed);
            json.put("total", totalTasks());
            json.put("completed", completedTasks());
            json.put("failed", failedTasks());
            if (createTime != null) json.put("create_time", createTime);
        } catch (Exception e) {}
        return json;
    }

    /** 从 JSON 反序列化 */
    public static PlanState fromJson(JSONObject json) {
        PlanState ps = new PlanState();
        JSONArray taskArr = json.optJSONArray("tasks");
        if (taskArr != null) {
            for (int i = 0; i < taskArr.length(); i++) {
                ps.tasks.add(Task.fromJson(taskArr.optJSONObject(i)));
            }
        }
        JSONObject active = json.optJSONObject("active_task");
        if (active != null) ps.activeTask = Task.fromJson(active);
        ps.roundsSinceUpdate = json.optInt("rounds_since_update", 0);
        ps.confirmed = json.optBoolean("confirmed", false);
        ps.createTime = json.optString("create_time", null);
        return ps;
    }

    /** 生成提示词用待办清单（纯文本，注入 LLM context） */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前任务计划\n");
        sb.append("进度: ").append(completedTasks()).append("/").append(totalTasks()).append(" 完成\n");
        if (activeTask != null) {
            sb.append("当前执行: [").append(activeTask.taskId).append("] ").append(activeTask.content).append("\n");
        }
        sb.append("\n待办清单:\n");
        for (Task t : tasks) {
            String icon;
            switch (t.status) {
                case COMPLETED: icon = "[x]"; break;
                case IN_PROGRESS: icon = "[>]"; break;
                case FAILED: icon = "[!]"; break;
                case PAUSED: icon = "[||]"; break;
                default: icon = "[ ]";
            }
            sb.append(icon).append(" ").append(t.taskId).append(": ").append(t.content);
            if (!t.deps.isEmpty()) {
                sb.append(" (依赖: ").append(String.join(",", t.deps)).append(")");
            }
            if (t.failReason != null) {
                sb.append(" [失败原因: ").append(t.failReason).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}