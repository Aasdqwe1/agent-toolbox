package com.example.agenttoolbox.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办任务数据模型
 * 
 * 支持多级拆解、依赖关系、优先级、截止时间
 */
public class Task {

    public enum Status {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed"),
        FAILED("failed"),
        PAUSED("paused"),
        SKIPPED("skipped");

        private final String value;
        Status(String v) { this.value = v; }
        public String value() { return value; }
    }

    public String taskId;
    public String parentId;          // 父任务ID，null=根任务
    public String content;           // 任务简述
    public String desc;              // 详细描述
    public Status status = Status.PENDING;
    public int priority = 3;        // 1紧急~5低优
    public List<String> deps = new ArrayList<>();  // 前置依赖任务ID
    public String deadline;         // "2026-07-10 18:00"
    public String estimatedCost;    // 预估耗时 "2h"
    public List<String> toolNeeds = new ArrayList<>(); // 需要的工具
    public String createTime;
    public String updateTime;
    public String failReason;
    public String skipReason;      // 跳过原因
    public String checkpoint;       // 验收标准
    public int retryCount = 0;
    public static final int MAX_RETRY = 3;

    public Task() {}

    public Task(String taskId, String content) {
        this.taskId = taskId;
        this.content = content;
        this.createTime = java.time.LocalDateTime.now().toString().replace("T", " ");
    }

    /** 是否所有依赖已完成 */
    public boolean depsResolved(List<Task> allTasks) {
        if (deps.isEmpty()) return true;
        for (String depId : deps) {
            boolean found = false;
            for (Task t : allTasks) {
                if (t.taskId.equals(depId)
                        && (t.status == Status.COMPLETED || t.status == Status.SKIPPED)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** 标记进行中 */
    public void markInProgress() {
        status = Status.IN_PROGRESS;
        updateTime = now();
    }

    /** 标记完成 */
    public void markCompleted() {
        status = Status.COMPLETED;
        updateTime = now();
    }

    /** 标记失败 */
    public void markFailed(String reason) {
        status = Status.FAILED;
        failReason = reason;
        retryCount++;
        updateTime = now();
    }

    /** 标记暂停 */
    public void markPaused() {
        status = Status.PAUSED;
        updateTime = now();
    }

    /** 标记跳过（有意识地不执行，区别于失败） */
    public void markSkipped(String reason) {
        status = Status.SKIPPED;
        skipReason = (reason != null && !reason.trim().isEmpty()) ? reason : "执行中决定跳过";
        updateTime = now();
    }

    /** 重置为待执行（重试） */
    public void resetToPending() {
        status = Status.PENDING;
        updateTime = now();
    }

    /** 是否可重试 */
    public boolean canRetry() {
        return retryCount < MAX_RETRY;
    }

    /** 序列化为 JSON */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("task_id", taskId);
            if (parentId != null) json.put("parent_id", parentId);
            json.put("content", content);
            if (desc != null) json.put("desc", desc);
            json.put("status", status.value());
            json.put("priority", priority);
            if (!deps.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String d : deps) arr.put(d);
                json.put("deps", arr);
            }
            if (deadline != null) json.put("deadline", deadline);
            if (estimatedCost != null) json.put("estimated_cost", estimatedCost);
            if (!toolNeeds.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String t : toolNeeds) arr.put(t);
                json.put("tool_need", arr);
            // 同时输出 tool_needs 别名，兼容不同客户端
            json.put("tool_needs", arr);
            }
            if (createTime != null) json.put("create_time", createTime);
            if (updateTime != null) json.put("update_time", updateTime);
            if (failReason != null) json.put("fail_reason", failReason);
            if (skipReason != null) json.put("skip_reason", skipReason);
            if (checkpoint != null) json.put("checkpoint", checkpoint);
        } catch (Exception e) {}
        return json;
    }

    /** 从 JSON 反序列化 */
    public static Task fromJson(JSONObject json) {
        Task t = new Task();
        t.taskId = json.optString("task_id");
        t.parentId = json.optString("parent_id", null);
        t.content = json.optString("content");
        t.desc = json.optString("desc", null);
        t.status = parseStatus(json.optString("status", "pending"));
        // 兼容 LLM 可能使用的 "completed": true 布尔字段
        if (t.status == Task.Status.PENDING && json.has("completed")) {
            t.status = json.optBoolean("completed", false) ? Task.Status.COMPLETED : Task.Status.PENDING;
        }
        t.priority = json.optInt("priority", 3);
        JSONArray deps = json.optJSONArray("deps");
        if (deps != null) for (int i = 0; i < deps.length(); i++) t.deps.add(deps.optString(i));
        t.deadline = json.optString("deadline", null);
        t.estimatedCost = json.optString("estimated_cost", null);
        JSONArray tools = json.optJSONArray("tool_needs");
        if (tools == null) tools = json.optJSONArray("tool_need");
        if (tools != null) for (int i = 0; i < tools.length(); i++) t.toolNeeds.add(tools.optString(i));
        t.createTime = json.optString("create_time", null);
        t.updateTime = json.optString("update_time", null);
        t.failReason = json.optString("fail_reason", null);
        t.skipReason = json.optString("skip_reason", null);
        t.checkpoint = json.optString("checkpoint", null);
        t.retryCount = json.optInt("retry_count", 0);
        return t;
    }

    private static Status parseStatus(String s) {
        if (s == null) return Status.PENDING;
        switch (s) {
            case "in_progress": return Status.IN_PROGRESS;
            case "completed": return Status.COMPLETED;
            case "failed": return Status.FAILED;
            case "paused": return Status.PAUSED;
            case "skipped": return Status.SKIPPED;
            default: return Status.PENDING;
        }
    }

    private static String now() {
        return java.time.LocalDateTime.now().toString().replace("T", " ");
    }
}