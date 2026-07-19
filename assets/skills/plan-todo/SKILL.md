---
skill_id: plan-todo
type: skill
name: 计划待办执行指南
description: 当用户任务包含3个以上步骤、需要依次完成多个子任务时，使用本指南创建和执行任务计划。所有字段名与系统源码完全一致。
when_to_use: |
  在以下情况使用：
    - 用户说"先...然后...接着..."
    - 用户说"步骤1、步骤2、步骤3"
    - 用户说"修改文件、运行测试、推送代码"（3步以上）
    - 你打算连续调用3个以上工具时
from: builtin
---

# 计划待办执行指南

## 核心原则

1. **计划是你输出的 JSON**：在 `result.content` 字符串中放置 `{"tasks":[...]}`
2. **系统会推进**：你不需要手动管理任务队列，只需在完成时发送 `plan_update`
3. **一个任务只做一件事**：每个任务调用一个工具
4. **优先文件搜索**：探索/定位文件时优先用 `file_search` 搜索目标文件，不要先用 `file_list` 无差别列出整个目录再翻找（尤其当用户要求「不要列出某目录」时）。仅当用户明确要求「列出目录」或需要查看目录全貌时才用 `file_list`

---

## 字段名速查表

| 位置 | 字段名 | 类型 | 说明 |
|------|--------|------|------|
| 任务对象 | `task_id` | string | 唯一标识，如 "T001" |
| 任务对象 | `content` | string | 任务描述 |
| 任务对象 | `deps` | string[] | 依赖的任务 ID 列表 |
| 任务对象 | `tool_needs` | string[] | 需要的工具名列表 |
| 任务对象 | `priority` | int | 1-5，默认3 |
| 任务对象 | `checkpoint` | string | 验收标准（可选） |
| plan_update | `action` | string | complete_task / mark_failed / update_plan |
| plan_update | `task_id` | string | 要操作的任务 ID |
| plan_update | `reason` | string | 失败原因（mark_failed 时使用） |
| plan_update | `plan` | object | 新计划（update_plan 时使用） |

---

## 第一步：创建计划

### 计划 JSON 格式（字段名必须完全匹配）

```json
{
  "tasks": [
    {
      "task_id": "T001",
      "content": "读取配置文件",
      "tool_needs": ["file_read"],
      "priority": 1
    },
    {
      "task_id": "T002",
      "content": "修改 timeout 参数",
      "deps": ["T001"],
      "tool_needs": ["file_write"]
    }
  ]
}
```

### 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `task_id` | ✅ | string | 唯一标识，格式 T001、T002... |
| `content` | ✅ | string | 一句话描述任务 |
| `deps` | ❌ | string[] | 依赖的前置任务 ID 列表 |
| `tool_needs` | ❌ | string[] | 预计使用的工具名 |
| `priority` | ❌ | int | 1-5，默认3 |
| `checkpoint` | ❌ | string | 验收标准 |

### 示例回复（创建计划）

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "我将按以下计划执行：{\"tasks\":[{\"task_id\":\"T001\",\"content\":\"搜索 README.md 等目标文件\",\"tool_needs\":[\"file_search\"]},{\"task_id\":\"T002\",\"content\":\"读取 README.md\",\"deps\":[\"T001\"],\"tool_needs\":[\"file_read\"]}]}"
  },
  "id": 1017
}
```

---

## 第二步：执行任务

创建计划后，系统会发送 `execute_task` 指令：

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "system",
    "action": "execute_task",
      "task": {
        "task_id": "T001",
        "content": "搜索目标文件",
        "tool_needs": ["file_search"]
      },
      "plan": {
        "total": 3,
        "completed": 0
      },
      "instruction": "请调用 file_search 工具执行此任务"
  },
  "id": 1017
}
```

### 执行规则

1. 根据 `task.tool_needs` 调用对应的 MCP 工具
2. 一个任务只调用一个工具
3. 工具执行后，在回复中附带 `plan_update` 标记完成

### 示例回复（执行任务）

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
    "params": {
      "name": "file_search",
      "arguments": {
        "keywords": ["README", "md"],
        "path": "/sdcard/Download/project",
        "search_content": false
      }
    },
  "result": {
    "plan_update": {
      "action": "complete_task",
      "task_id": "T001"
    }
  },
  "id": 1017
}
```

---

## 第三步：推进计划（plan_update）

### plan_update 字段格式

`plan_update` 放在 `result` 顶层，与 `type` 同级。

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "任务完成。",
    "plan_update": {
      "action": "complete_task",
      "task_id": "T001"
    }
  },
  "id": 1017
}
```

### 三种 action

| action | 必填字段 | 说明 |
|--------|----------|------|
| `complete_task` | `task_id` | 标记任务完成（别名 `mark_done` 同样生效） |
| `mark_failed` | `task_id`, `reason` | 标记任务失败，可在 MAX_RETRY 内重试 |
| `update_plan` | `plan` | 替换整个计划 |

### 示例（完成任务）

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "配置文件已修改。",
    "plan_update": {
      "action": "complete_task",
      "task_id": "T002"
    }
  },
  "id": 1017
}
```

### 示例（任务失败）

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "文件不存在。",
    "plan_update": {
      "action": "mark_failed",
      "task_id": "T001",
      "reason": "FileNotFoundError: settings.conf not found"
    }
  },
  "id": 1017
}
```

### 示例（替换计划）

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "根据情况调整计划。",
    "plan_update": {
      "action": "update_plan",
      "plan": {
        "tasks": [
          {"task_id": "T001", "content": "新任务1"},
          {"task_id": "T002", "content": "新任务2", "deps": ["T001"]}
        ]
      }
    }
  },
  "id": 1017
}
```

---

## 第四步：ask 工具配合

调用 ask 后，**不要立即发送 plan_update**。等用户回答后，再完成任务。

### 第1轮：调用 ask

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "question": "是否删除 config.bak？",
      "options": ["是", "否"]
    }
  },
  "id": 1017
}
```

（注意：这里**没有** plan_update）

### 第2轮：用户回答后

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "用户确认删除。",
    "plan_update": {
      "action": "complete_task",
      "task_id": "T003"
    }
  },
  "id": 1017
}
```

---

## 第五步：完成总结

收到 `plan_complete` 后，用自然语言总结：

```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "## ✅ 全部完成\n\n1. 已读取配置文件\n2. 已修改 timeout=60\n3. 已保存更改\n\n所有任务执行成功！"
  },
  "id": 1017
}
```

---

## 常见错误

### 错误1：字段名错误

❌ 错误字段名：`taskId`、`dependencies`、`tools`

✅ 正确字段名：`task_id`、`deps`、`tool_needs`

### 错误2：plan_update 位置错误

❌ 放在 content 里：
```json
{"result": {"type": "reply", "content": "完成。{\"plan_update\":...}"}}
```

✅ 放在 result 顶层：
```json
{"result": {"type": "reply", "content": "完成。", "plan_update": {"action": "complete_task"}}}
```

### 错误3：ask 后立即 plan_update

❌ 错误：调用 ask 后立即发送 plan_update

✅ 正确：先调用 ask，等用户回答后再 plan_update

---

## 速查表

| 情况 | 动作 | 字段 |
|------|------|------|
| 创建计划 | 输出 tasks JSON | `task_id`, `content`, `deps`, `tool_needs` |
| 任务完成 | `complete_task` | `action`, `task_id` |
| 任务失败 | `mark_failed` | `action`, `task_id`, `reason` |
| 替换计划 | `update_plan` | `action`, `plan` |
| 用户确认 | 调用 ask，等回答后再 `complete_task` | - |

---

## 完整示例

用户："帮我列出目录，然后生成文档，最后推送"

### 第1轮 - 创建
```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "计划：{\"tasks\":[{\"task_id\":\"T001\",\"content\":\"列出目录\",\"tool_needs\":[\"file_list\"]},{\"task_id\":\"T002\",\"content\":\"生成文档\",\"tool_needs\":[\"python\"],\"deps\":[\"T001\"]},{\"task_id\":\"T003\",\"content\":\"推送\",\"tool_needs\":[\"shell\"],\"deps\":[\"T002\"]}]}"
  },
  "id": 1017
}
```

### 第2轮 - 执行 T001
```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "目录已列出。",
    "plan_update": {"action": "complete_task", "task_id": "T001"}
  },
  "id": 1017
}
```

### 第3轮 - 执行 T002
```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "文档已生成。",
    "plan_update": {"action": "complete_task", "task_id": "T002"}
  },
  "id": 1017
}
```

### 第4轮 - 执行 T003
```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "推送成功。",
    "plan_update": {"action": "complete_task", "task_id": "T003"}
  },
  "id": 1017
}
```

### 第5轮 - 总结
```json
{
  "jsonrpc": "2.0",
  "result": {
    "type": "reply",
    "content": "## ✅ 全部完成\n\n所有任务已成功执行！"
  },
  "id": 1017
}
```
