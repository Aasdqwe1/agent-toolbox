# Markdown格式化修复说明

## 问题分析

### 原始问题
在 `问题.txt` 中发现，DeepSeek网页版显示的回复内容格式良好，但通过 WebView 提取时：
- 后端接收到的是带 `<br>` 标签的plain text
- 丧失了原始的Markdown结构化格式
- 导致前端显示缺乏格式化

### 根本原因
`DeepSeekChatBridge.java` 中的 `getAssistantReply()` 函数使用 `innerText` 提取纯文本：
```javascript
var txt = (el.innerText || el.textContent || '').trim();
```

这种方式会：
1. 丧失所有HTML标签信息
2. 导致段落、列表、代码块等结构信息丢失
3. 使得后端无法接收到原始的Markdown格式

## 修复方案

### 核心改进
1. **HTML内容提取**：改用 `innerHTML` 而不是 `innerText`
2. **HTML到Markdown转换**：添加 `htmlToMarkdown()` 函数，将HTML结构转换为Markdown格式
3. **多行内容支持**：使用 `[\s\S]*?` 正则模式替代 `.*?`，支持跨行内容
4. **HTML实体解码**：正确处理 `&quot;`、`&lt;`、`&gt;` 等实体

### 新增功能

#### 1. getAssistantReply() - 改进版本
```javascript
function getAssistantReply(el) {
  if (!el) return null;
  // 尝试获取HTML内容以保持Markdown格式化
  var html = (el.innerHTML || '').trim();
  if (html && html.length > 0) {
    // 转换HTML为Markdown格式，保持文档结构
    var md = htmlToMarkdown(html);
    if (md && md.length > 0) {
      return md;
    }
  }
  // 备用方案：如果HTML转换失败，使用plaintext
  var txt = (el.innerText || el.textContent || '').trim();
  return txt || null;
}
```

#### 2. htmlToMarkdown() - HTML到Markdown转换
支持的转换：
- `<p>` → 段落（保留段落间空行）
- `<br>` → 换行符
- `<div>` → 行
- `<li>` → Markdown列表项
- `<strong>`/`<b>` → `**bold**`
- `<em>`/`<i>` → `*italic*`
- `<h1>`-`<h4>` → Markdown标题
- `<a href="...">` → Markdown链接
- `<pre><code>` → Markdown代码块
- `<code>` → 行内代码
- HTML实体 → 正确字符

## 数据流

```
DeepSeek WebView
    ↓
getAssistantReply() [改进版本]
    ↓
htmlToMarkdown() [转换HTML→Markdown]
    ↓
onDeepSeekReply(requestId, markdownContent)
    ↓
DeepSeekChatBridge
    ↓
McpServer/HTTP响应
    ↓
后端/前端显示
```

## 测试建议

1. **格式化内容**：包含列表、代码块、标题的回复
2. **多行内容**：验证多行代码和长段落处理
3. **特殊字符**：包含`<`、`>`、`&`等HTML实体的内容
4. **备用方案**：当HTML提取失败时，确保仍能返回plain text

## 兼容性

- ✅ 完全兼容现有代码
- ✅ 提供fallback机制
- ✅ 使用JavaScript标准特性（无ES6+依赖）
- ✅ 支持所有现代浏览器

## 改进前后对比

### 改进前
```
LLM回复（Markdown）:
我有几个建议：

1. 使用缓存
2. 优化数据库查询

代码示例：
const cache = new Map();

前端显示:
我有几个建议：<br><br>1. 使用缓存<br>2. 优化数据库查询<br><br>代码示例：<br>const cache = new Map();
```

### 改进后
```
LLM回复（Markdown）:
我有几个建议：

1. 使用缓存
2. 优化数据库查询

代码示例：
const cache = new Map();

前端显示:
我有几个建议：

1. 使用缓存
2. 优化数据库查询

代码示例：
```
const cache = new Map();
```
```

## 文件修改

- **修改文件**：`src/com/example/agenttoolbox/DeepSeekChatBridge.java`
- **修改行**：273-325（`getAssistantReply()` 函数和新增 `htmlToMarkdown()` 函数）
