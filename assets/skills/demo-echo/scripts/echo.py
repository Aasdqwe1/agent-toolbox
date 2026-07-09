# demo_echo 工具脚本
# 入参由 SkillTool 以 base64(JSON) 注入为 __ARGS__（dict）
import sys

text = __ARGS__.get("text", "")
print("echo:", text)
