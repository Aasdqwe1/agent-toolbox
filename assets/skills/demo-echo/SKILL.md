---
name: demo-echo
description: 演示 app 端 skill 工具路径的最小示例技能（提供一个 demo_echo 工具回显输入）
when_to_use: 需要验证 skill 工具是否被正确注册与执行时
---
# demo-echo

这是一个**演示技能**，提供一个 `demo_echo` 工具，回显输入文本，用于端到端验证「app 支持 skill」的工具路径：

- 工具由 `SkillManager` 从 `tools.json` 发现并注册进 `ToolManager`；
- 通过 `tools/call` 调用，经 `PythonBridge` 执行 `scripts/echo.py`；
- 入参以 `__ARGS__` 注入（见 SkillTool 实现）。
