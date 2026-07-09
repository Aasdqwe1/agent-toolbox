## 五、MT 管理器与 APK MCP

> 整理自 MT 管理器官方文档，日期：2026-07-09

### 5.1 APK MCP

APK MCP 是 MT 管理器提供的本机 MCP 服务，可用 AI 聊天工具、Agent 等 MCP 客户端连接。本应用已通过 `ApkMcpClient` + `mergeApkTools()` 自动发现并注册 `mt_apk_*` 系列工具到 `ToolManager`。

#### 启动与连接

- 入口：MT 主界面 → 侧拉栏 → **工具**分组 → **APK MCP**
- AI 客户端和 MT 在同一台手机 → 使用**本地地址**
- AI 客户端在另一台设备 → 使用**局域网地址**（需同一局域网）

#### 工作区机制

| 概念 | 说明 |
|---|---|
| **工作区** | AI 首次打开 APK 时创建的只读数据区，后续读取 Manifest / 资源 / 布局 / 字符串 / Dex/Smali 均基于此 |
| **编辑会话** | 修改 APK 时在工作区基础上创建，单独记录修改，不污染工作区只读数据 |
| **工作区复用** | 重复打开同一 APK 可复用已有工作区，减少重复解析时间 |
| **会话隔离** | 一个工作区可支持多个编辑会话，相互隔离，可同时尝试不同修改方案 |
| **自动清理** | 超出设置保留数量时自动删除最近未访问的工作区（不删原始 APK 和已生成的新 APK） |

#### 可用 MCP 工具分类

**找到并打开 APK：**

| 工具 | 作用 |
|---|---|
| `mt_apk_list_available_apks` | 列出可打开的 APK 目标（MCP 操作目录 + 当前 APK 文件） |
| `mt_apk_open` | 打开 APK，创建/复用只读工作区，返回应用信息、Manifest 摘要、资源和 Dex 概况 |

**阅读与检索：**

| 工具 | 作用 |
|---|---|
| `mt_apk_list` | 分页列出已打开 APK 的结构（ZIP 条目、Dex 类、资源表条目） |
| `mt_apk_outline_class` | 查看 Dex 类的字段和方法轮廓 |
| `mt_apk_read_text` | 读取文本内容（文本 ZIP 条目、解码后 AXML、Dex 类/方法 Smali） |
| `mt_apk_read_zip_bytes` | 读取 ZIP 条目原始字节 |
| `mt_apk_read_resource` | 读取 resources.arsc 中的资源值，返回可修改的 valueXml |
| `mt_apk_search` | 搜索 ZIP 路径、AXML 文本、资源名/值、Dex 名称/字符串、Smali 内容 |
| `mt_apk_xref_dex` | 查找 Dex 类/字段/方法的引用位置 |
| `mt_apk_xref_resource` | 查找资源 ID 在 Dex、AXML 或资源表中的引用位置 |
| `mt_apk_continue` | 继续读取分页结果 |

**修改与重新打包：**

| 工具 | 作用 |
|---|---|
| `mt_apk_edit_open` | 基于只读工作区创建编辑会话 |
| `mt_apk_edit_text` | 修改文本类目标（Smali、AXML、普通文本 ZIP 条目），也可创建缺失目标或删除条目 |
| `mt_apk_edit_resource` | 修改资源表中的资源值（字符串、颜色、布尔值、整数、引用等） |
| `mt_apk_edit_check` | 检查编辑会话状态，执行构建前检查 |
| `mt_apk_build` | 重新打包并签名，生成新 APK（签名密钥来自 APK MCP 设置） |

**清理：**

| 工具 | 作用 |
|---|---|
| `mt_apk_close` | 清理临时工作区，删除对应工作区和编辑会话状态（不删原始 APK 或已生成的新 APK） |

> 普通历史工作区不支持 AI 主动清理，MT 会根据设置自动清理或用户手动清理。

#### 不支持的功能

| 功能 | 说明 |
|---|---|
| **so 文件分析与修改** | AI 可看到 so 文件，但不能反编译、分析或修改 native 代码 |
| **反编译为 Java 代码** | AI 直接基于 Smali 分析和修改，比反编译 Java 更稳定 |
| **添加新语言包/词条** | 只能编辑已有词条内容；如需添加，先用 ARSC 编辑器手动处理 |

### 5.2 MT 管理器插件接口

本应用可通过 skill 注册工具来调用 MT 管理器能力。以下为 MT 插件系统的主要接口，供开发参考。

**翻译引擎接口：**

| 接口 | 说明 |
|---|---|
| `TranslationEngine` | 单文本翻译；支持批量优化与超长文本自动拆分 |
| `BatchTranslationEngine` | 原生批量翻译，支持多文本数组与自定义分批策略 |

**文本编辑器扩展接口：**

| 接口 | 说明 |
|---|---|
| `TextEditorFunction` | 快捷功能扩展，在底部功能栏添加自定义编辑操作 |
| `TextEditorFloatingMenu` | 浮动菜单扩展，在选中文本时显示快捷操作 |
| `TextEditorToolMenu` | 工具菜单扩展，在编辑器工具栏添加菜单项 |

**设置界面接口：**

| 接口 | 说明 |
|---|---|
| `PluginPreference` | 设置界面构建器，提供开关、输入框、列表等配置选项 |

#### 开发要求

| 项目 | 要求 |
|---|---|
| MT 管理器最低版本 | 2.26.3+ |
| Android Studio | Hedgehog (2023.1.1) 或更高 |
| AGP | 8.1.0 或更高 |
| VIP 要求 | 插件开发、测试和安装均需 MT 管理器 VIP 权限 |
| 语言 | Java 11+ / Kotlin（推荐最新稳定版） |
