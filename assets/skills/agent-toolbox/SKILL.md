---
name: agent-toolbox
description: 用于 agent-toolbox（Android MCP 工具箱）项目的开发与调试。当用户要修改 McpServer / TaskManager / DeepSeekChatBridge / DeepSeekActivity / JavaScriptBridge 等 Java 后端，或 assets/test_client.html 前端（Markdown 渲染、KaTeX 数学公式、代码块复制），或涉及 JSON-RPC 计划/任务执行（plan、task、execute_task、tryExtractPlan、plan_update）、WebView 抓取 DeepSeek 回复、或用 git 推送该项目时触发本 skill。关键词：agent-toolbox、MCP 工具箱、DeepSeek、WebView、计划待办、execute_task、renderMarkdown、KaTeX。
when_to_use: 修改 McpServer/TaskManager/DeepSeekChatBridge 等 Java 后端、前端 renderMarkdown、JSON-RPC 计划执行、或用 git 推送该项目时
---

# agent-toolbox 开发技能

## Overview

agent-toolbox 是一个用 WebView 包裹 DeepSeek 网页对话、作为 MCP 智能体工具箱的 Android 应用。它自带一个前端页面 `assets/test_client.html`（标题「MCP 工具箱」），由 `McpServer` 的内嵌 HTTP 服务器以 localhost 提供（安全上下文，`navigator.clipboard` 可用），用于展示智能体回复与工具结果，并支持 Markdown / 数学公式 / 代码块复制。

后端为 Java（`src/main/java/com/example/agenttoolbox/`）。本 skill 固化了该项目反复踩过的坑与协议约定，避免每次重新推理。

## Project Map

```
src/main/java/com/example/agenttoolbox/
├── mcp/
│   ├── McpServer.java         # 内嵌 HTTP/SSE 服务、JSON-RPC 处理、计划提取与执行、buildPlanMessage
│   ├── TaskManager.java       # 计划加载/确认/选取/推进；loadPlan 归一化空字段
│   └── Task.java              # 任务数据模型 + fromJson/toJson
├── DeepSeekChatBridge.java    # 抓取 DeepSeek 页面回复（extractReply 取 p.ds-markdown-paragraph / pre）
├── DeepSeekActivity.java      # WebView 宿主；onPageFinished 注入 observer 脚本
└── JavaScriptBridge.java      # JS 桥（observer 注入）
assets/test_client.html        # 真正的前端：marked + sanitizeHtml + KaTeX
```

## Critical Conventions（务必遵守）

### 1. 计划/任务协议（JSON-RPC）— `tryExtractPlan` 判定
- LLM 回复：`{"jsonrpc":"2.0","result":{"type":"reply","content":"<文本或计划>"},"id":N}`。
- 计划常以前言 + 换行 + `{"tasks":[...]}` 形式出现在 `result.content` 中，**不一定是纯 JSON**。
- 提取计划必须用「平衡括号提取 + 占比阈值(≥1/3)」：在文本里找到完整 `{"tasks":[...]}` JSON 且它占 content 大部分才认作计划。
  - ❌ 错误：用 `content.indexOf('{')` 直接 `new JSONObject(sub)` —— `org.json` 会忽略 `}` 后多余字符，会把讲解文字里举例的 `{"tasks":[...]}` 误当真计划（id:1007 误触发、id:1003 真实计划反被拒的教训）。
  - ✅ 正确：遍历顶层 `{`，用 `matchingBrace` 取匹配 `}`，要求 `candidate.length()*3 >= s.length()`。
- 任务字段：`task_id`、`content`、`priority`(1-5)、`deps[]`、`tool_needs[]`、`checkpoint`。
- 流程：`tryExtractPlan` → `loadPlan`（空 `task_id` 自动补 `T001…`、空 `content` 回退填充）→ `selectNextTask`（置 IN_PROGRESS）→ `buildPlanMessage("execute_task", task, planState, …)` 发结构化系统消息，指令 LLM 调工具后用 `plan_update` 推进。
- LLM 推进：回复里带 `plan_update`（`action`: complete_task/mark_done/mark_failed/update_plan + `task_id`）。

### 2. 前端 `renderMarkdown`（test_client.html）
- **必须在 `marked.parse` 前**用占位符保护：①围栏+行内代码块；②块级数学 `$$…$$` / `\[…\]`。解析后还原代码为 HTML、还原数学为**单行** `$$ … $$`（换行合并为空格，保留 `\\`）。
  - 原因：KaTeX `$$` 定界符不跨多行；`marked` 会把 LaTeX 的 `\\` 当转义吞掉。
- 复制按钮与数学公式渲染都在**应用自己的前端 HTML** 里做，绝不能注入到 `chat.deepseek.com` 页面（曾误注入 MathJax/复制按钮，已回退）。
- KaTeX 经 CDN `<script defer>` 引入；`MutationObserver` 监听 `chatMessages` → `enhanceContent` → `renderMathInContainer`(KaTeX auto-render) + `addCodeCopyButtons`。
- `sanitizeHtml` 用 DOMParser + 白名单（P/BR/STRONG/B/EM/I/U/CODE/PRE/H1-6/UL/OL/LI/BLOCKQUOTE/A/TABLE…），**不含 BUTTON 也不含 KaTeX 输出 span** → 数学与复制按钮必须在 sanitize 之后对真实 DOM 后处理。

### 3. 推送与构建约束
- ⚠️ 本沙箱**无法编译 Android**（无 SDK；gradle 指向 AIDE 的 JDK 路径）。改动用 **node 复现核心逻辑**验证（如 `tryExtractPlan` 平衡括号+占比、`loadPlan` 归一化），不要依赖 gradle build。
- 推送模式：把 remote 设为 `https://<USER>:<TOKEN>@github.com/<USER>/agent-toolbox.git` → `git push origin main` → 立刻把 remote 重置为公开 `https://github.com/<USER>/agent-toolbox.git`。不要长期保留带 token 的 remote。
- **不要把真实 token 写进会被提交的文件**（含本 skill）。

## References
- `references/conventions.md` — 协议、前端、推送的完整细节与踩坑记录。
