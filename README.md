# Agent工具箱 - MCP服务端

基于 **AIDE Pro** 开发的安卓端 MCP（Model Context Protocol）服务端应用，完全遵循 JSON-RPC 2.0 协议规范，可与网页端 Agent 客户端无缝对接。内置 DeepSeek 网页版集成，一键调用本地工具。

---

## 功能特性

- ✅ 完整实现 MCP 协议标准（JSON-RPC 2.0 over HTTP）
- ✅ 内置 4 款实用工具：
  - `math_calculator` - 高精度数学计算（基于栈的表达式解析）
  - `http_request` - 通用 HTTP 请求（支持 GET/POST/PUT/DELETE）
  - `file_read` - 文件读取（应用内部存储）
  - `file_write` - 文件写入（支持覆盖或追加）
- ✅ 可视化服务控制界面 + 实时请求日志
- ✅ DeepSeek 网页版集成（登录检测、新会话、刷新）
- ✅ 页面源码提取功能（evaluateJavascript 直接返回，无 alert 依赖）
- ✅ MCP DOM 监听脚本（自动检测页面中的工具调用）
- ✅ 支持跨域访问（CORS）
- ✅ 纯 Java 实现，无第三方依赖

---

## 快速开始

### 1. 导入到 AIDE Pro

**方法一：直接打开项目**
1. 将整个项目文件夹复制到手机存储
2. 打开 AIDE Pro → 选择「打开项目」→ 选择该文件夹

**方法二：手动创建**
1. 打开 AIDE Pro，新建 Android App 空白项目
2. 应用名称：`Agent工具箱`，包名：`com.example.agenttoolbox`
3. 最低 SDK：API 21 (Android 5.0)
4. 将本仓库的文件按目录结构复制到对应位置

### 2. 启动 MCP 服务

1. 安装并运行应用
2. 点击 **「启动MCP服务」** 按钮
3. 记录显示的监听地址（如 `http://192.168.1.100:8080`）
4. 在同一局域网的浏览器访问该地址，可打开测试客户端

### 3. 使用 DeepSeek 集成

1. 点击 **「打开 DeepSeek 助手」**
2. 登录你的 DeepSeek 账号
3. 顶栏「提取源码」可复制当前页面 HTML 到剪贴板

---

## 项目结构

```
AgentToolbox/
├── AndroidManifest.xml              # 应用清单（权限 + 2 个 Activity）
├── assets/
│   └── test_client.html             # MCP 测试客户端（HTTP 服务返回）
├── res/
│   ├── layout/
│   │   ├── activity_main.xml        # 主界面（服务控制 + 日志）
│   │   └── activity_deepseek.xml    # DeepSeek 界面（WebView + 工具栏）
│   └── values/
│       └── strings.xml              # 应用资源
├── proguard-rules.pro               # 混淆规则
├── project.properties               # AIDE 项目配置
└── src/
    └── com/example/agenttoolbox/
        ├── MainActivity.java        # 主界面（服务控制）
        ├── DeepSeekActivity.java    # DeepSeek WebView 集成
        ├── JavaScriptBridge.java    # JS <-> Java 桥（工具调用）
        ├── mcp/
        │   ├── McpServer.java       # MCP HTTP 服务端（核心）
        │   ├── JsonRpcRequest.java  # JSON-RPC 请求解析
        │   └── JsonRpcResponse.java # JSON-RPC 响应构造
        └── tools/
            ├── Tool.java            # 工具接口
            ├── ToolManager.java     # 工具管理器
            ├── MathCalculatorTool.java
            ├── HttpRequestTool.java
            ├── FileReadTool.java
            └── FileWriteTool.java
```

---

## MCP 协议接口

### 支持的方法

| 方法名 | 说明 |
|--------|------|
| `initialize` | 初始化连接 |
| `tools/list` | 获取所有工具列表 |
| `tools/call` | 调用指定工具 |
| `notifications/initialized` | 初始化完成通知 |

### 错误码（JSON-RPC 2.0 标准）

| 错误码 | 说明 |
|--------|------|
| -32700 | 解析错误 |
| -32600 | 请求无效 |
| -32601 | 方法不存在 |
| -32602 | 参数无效 |
| -32603 | 内部错误 |

### JavaScript 调用示例

```javascript
// 获取工具列表
fetch('http://手机IP:8080', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 'req_001',
    method: 'tools/list',
    params: {}
  })
})
.then(res => res.json())
.then(data => console.log(data));

// 调用数学计算工具
fetch('http://手机IP:8080', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 'req_002',
    method: 'tools/call',
    params: {
      name: 'math_calculator',
      arguments: { expression: '2 + 3 * 4' }
    }
  })
})
.then(res => res.json())
.then(data => console.log(data));
```

---

## 扩展开发：添加新工具

### 步骤 1：创建工具类

```java
package com.example.agenttoolbox.tools;

import org.json.JSONObject;

public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public String getDescription() {
        return "我的自定义工具";
    }

    @Override
    public JSONObject getInputSchema() {
        // 按照 JSON Schema 返回参数定义
        JSONObject schema = new JSONObject();
        // ...
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        // 实现工具逻辑
        return "执行结果";
    }
}
```

### 步骤 2：在 ToolManager 注册

```java
// ToolManager.java 构造方法中添加：
registerTool(new MyTool());
```

---

## 注意事项

1. **网络权限**：应用已申请 INTERNET 权限，请确保手机网络正常
2. **端口冲突**：默认使用 8080 端口，如需修改请在 `MainActivity.java` 中修改 `PORT` 常量
3. **安全限制**：文件操作仅限应用内部存储目录（`/data/data/com.example.agenttoolbox/files/`），已内置路径遍历攻击防护
4. **同一局域网**：网页端和手机必须在同一 WiFi 网络下才能访问 MCP 服务
5. **源码提取**：使用 `evaluateJavascript` 方式直接返回结果（无需依赖 alert，避免超时问题）

---

## 版本信息

- **版本**：1.2.0
- **协议**：MCP 2024-11-05（JSON-RPC 2.0 over HTTP）
- **最低 Android 版本**：API 21 (Android 5.0)
- **目标 SDK**：API 30 (Android 11)

### 更新日志

**v1.2.0**
- 修复流式回复超时问题（将心跳检测超时从 8 秒调整为 30 秒）
- 优化长时间运行工具调用的 JSON 解析（避免因心跳中断导致 JSON 不完整）
- 改进对超过 8 秒的工具执行（如长时间 HTTP 请求、文件处理等）的支持

**v1.1.0**
- 修复 DeepSeekActivity 中「提取源码」功能超时问题（改用 evaluateJavascript 替代 alert 通信）
- 新增页面源码提取返回结果解析与错误处理
- 优化超时时间从 5s 调整为 8s，适配大页面提取

**v1.0.0**
- 初始版本：MCP 服务端 + DeepSeek 集成 + 4 个内置工具
