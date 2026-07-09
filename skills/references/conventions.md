# agent-toolbox 约定与踩坑记录

本文件是 `agent-toolbox` skill 的详细参考，固化项目协议、前端规则与推送流程。

## 一、JSON-RPC 计划/任务协议

### 消息封装
```json
{"jsonrpc":"2.0","result":{"type":"reply","content":"<文本或计划>"},"id":1003}
```
- `type:"reply"`：普通文本或含计划。
- `type:"system"` + `action:"execute_task"`：系统下发的"去执行某任务"指令。

### 计划如何出现在 content 中
真实 LLM 输出通常是：
```
好的，我为你生成了一个包含 N 个任务的计划……（前言）

{"tasks":[{"task_id":"T001","content":"...","priority":1,"deps":[],"tool_needs":["python"],"checkpoint":"..."}, ...]}
```
即**前言 + 换行 + 计划 JSON**，不一定是纯 JSON。

### tryExtractPlan 正确实现（防误判）
```java
private JSONObject tryExtractPlan(String content) {
    if (content == null || content.isEmpty()) return null;
    String s = content.trim();
    if (s.startsWith("```")) { int nl = s.indexOf('\n'); s = (nl>=0?s.substring(nl+1):s.substring(3)).trim(); }
    if (s.endsWith("```")) s = s.substring(0, s.length()-3).trim();
    for (int i = s.indexOf('{'); i >= 0; i = s.indexOf('{', i+1)) {
        int close = matchingBrace(s, i);
        if (close < 0) continue;
        String candidate = s.substring(i, close + 1);
        try {
            JSONObject json = new JSONObject(candidate);
            if (json.has("tasks") && json.optJSONArray("tasks") != null
                    && candidate.length() * 3 >= s.length()) {  // 占比 ≥ 1/3
                return json;
            }
        } catch (Exception e) {}
    }
    return null;
}
private int matchingBrace(String s, int openIdx) { /* 处理字符串内括号/转义，返回匹配 '}' 下标 */ }
```
判定依据：
- **平衡括号提取**：避免 `org.json` 忽略 `}` 后多余字符的坑。
- **占比 ≥ 1/3**：真实计划占正文 ~90%（如 id:1003 为 93.7%），讲解里的示例仅占 ~4%（如 id:1007 为 4.3%），靠占比区分。
- 同时兼容 ```` ```json ... ``` ```` 围栏。

### 任务字段
| 字段 | 说明 |
|---|---|
| `task_id` | 任务标识；缺失时 `loadPlan` 自动补 `T001…`（去重） |
| `content` | 任务简述；缺失时回退为 `任务 Txxx` |
| `priority` | 1(紧急)~5(低优)，默认 3 |
| `deps` | 前置依赖 task_id 列表 |
| `tool_needs` | 所需工具，如 `["python"]` |
| `checkpoint` | 验收标准 |

### 执行流程
1. `tryExtractPlan(content)` 命中 → `taskManager.loadPlan(planState, planJson)`（顺带 `planState.confirmed = true`）。
2. `selectNextTask(planState)`：标记首个任务 `IN_PROGRESS`，写入 `planState.activeTask`。
3. `buildPlanMessage("execute_task", task, planState, …)` → 发结构化系统消息，含 `instruction`："请按计划执行此任务，调用对应工具。完成后在回复中使用 plan_update 推进计划"。
4. LLM 完成后在回复里带 `plan_update`（`action`: `complete_task`/`mark_done`/`mark_failed`/`update_plan` + `task_id`）→ 选取下一任务继续，直到 `allCompleted()` 发 `plan_complete`。
5. `buildPlanMessage` 对 `task_id`/`content` 做兜底，绝不下发空值（空则 `"T?"` / `"(未提供任务内容)"`）。

## 二、前端（assets/test_client.html）

### renderMarkdown 必须保护代码块与块级数学
```javascript
function renderMarkdown(text) {
    if (!text) return '';
    try {
        var decoded = unescapeHtml(text);
        if (typeof marked !== 'undefined') {
            var mathStore = [], codeStore = [];
            var s = decoded
                .replace(/```[\s\S]*?```/g, function(m){ var i=codeStore.length; codeStore.push(marked.parse(m)); return '@@CODE'+i+'@@'; })
                .replace(/`[^`\n]+`/g,     function(m){ var i=codeStore.length; codeStore.push(marked.parse(m)); return '@@CODE'+i+'@@'; });
            s = s.replace(/\$\$([\s\S]+?)\$\$/g, function(m,p1){ mathStore.push(p1); return '@@MATHB'+(mathStore.length-1)+'@@'; });
            s = s.replace(/\\\[([\s\S]+?)\\\]/g, function(m,p1){ mathStore.push(p1); return '@@MATHB'+(mathStore.length-1)+'@@'; });
            var html = marked.parse(s);
            html = html.replace(/@@CODE(\d+)@@/g, function(m,i){ return codeStore[+i]; });
            html = html.replace(/@@MATHB(\d+)@@/g, function(m,i){ return '$$ ' + mathStore[+i].replace(/\n/g,' ') + ' $$'; });
            return sanitizeHtml(html);
        }
    } catch (e) { /* 回退为 <pre> 纯文本 */ }
    return '<pre …>' + escapeHtml(text) + '</pre>';
}
```
要点：多行 `$$…$$` 必须还原成**单行** `$$ … $$` 供 KaTeX auto-render；`\\` 反斜杠序列要原样保留（矩阵换行）。

### 复制按钮 / 数学在应用自己的 HTML
- 复制按钮和 KaTeX 都加在 `test_client.html`（MCP 工具箱页面），**不要**注入到 `chat.deepseek.com`。
- `MutationObserver` 监听 `chatMessages` → `enhanceContent` → `renderMathInContainer`(KaTeX auto-render，delimiters 含 `$$`/`$`/`\(`/`\[`，ignoredTags 含 `pre`/`code`) + `addCodeCopyButtons`（把每个 `pre` 包进 `.code-block-wrap` 并加 `.code-copy-btn`）。

### sanitizeHtml 白名单
P, BR, STRONG, B, EM, I, U, CODE, PRE, H1–H6, UL, OL, LI, BLOCKQUOTE, A, TABLE, THEAD, TBODY, TR, TH, TD。
**不含 BUTTON、也不含 KaTeX 输出的 span** → 数学与复制按钮必须在 sanitize **之后**对真实 DOM 后处理。

## 三、推送与构建

### 不能 gradle build
本沙箱无 Android SDK，`gradle` 指向 AIDE 的 JDK，无法编译。验证 Java 逻辑请用 **node 复现**：把 `tryExtractPlan`/`loadPlan` 等纯逻辑抽成 JS，用真实抓取载荷（id:1003 计划、id:1007 讲解）跑断言。

### 推送模式（token 用完即重置）
```bash
git remote set-url origin https://<USER>:<TOKEN>@github.com/<USER>/agent-toolbox.git
git push origin main
git remote set-url origin https://github.com/<USER>/agent-toolbox.git   # 立刻重置为公开
```
- 不要把真实 token 写进任何会被提交的文件（含本 skill / 提交信息 / 代码）。
- 改完先本地提交，再按上面推送，最后确认 `git remote -v` 已是公开地址。

## 四、常见误判对照

| 现象 | 原因 | 修正 |
|---|---|---|
| 讲解文字里举例 `{"tasks":…}` 被当成计划触发空 execute_task | `tryExtractPlan` 用 `indexOf('{')` + org.json 忽略尾部 | 平衡括号提取 + 占比 ≥1/3 |
| 真实计划（前言+计划）没生成任务 | 上一条的过度收紧版要求"整段都是 JSON" | 改为占比阈值，接受前言+计划 |
| 块级数学 `$$…$$` 不渲染 | KaTeX `$$` 不跨多行；marked 吞 `\\` | renderMarkdown 占位符保护，多行转单行 |
| 复制按钮/公式出现在 DeepSeek 网页而非工具箱 | 误注入到 chat.deepseek.com | 改在 test_client.html 内做 |
