package com.example.agenttoolbox.skills;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 数据模型 —— 一个 skill 包的描述。
 *
 * skill 包结构：
 *   skills/<id>/
 *     ├── SKILL.md            # 前置元数据(name/description/when_to_use) + Markdown 正文
 *     ├── tools.json          # 可选：可执行工具定义
 *     ├── references/         # 可选：知识文件（按需经 skill_read 读取）
 *     └── scripts/            # 可选：工具引用的脚本
 *
 * 内置 skill 来自 assets/skills/（fromAssets=true，dir=null，内容在发现时读入内存）；
 * 用户 skill 来自外部运行时目录（fromAssets=false，dir 指向真实目录）。
 */
public class Skill {

    public String id;
    public String name;
    public String description;
    public String whenToUse;
    public String body = "";                 // SKILL.md 正文（frontmatter 之后）
    public File dir;                          // 运行时目录；assets 内置时为 null
    public boolean fromAssets;               // true=assets 内置
    public String override = "";             // frontmatter override 标记，"true" 时允许 runtime 覆盖内置
    public List<ToolDef> tools = new ArrayList<>();
    public List<String> referenceNames = new ArrayList<>();
    public Map<String, String> references = new HashMap<>(); // 参考文件名 -> 内容

    /** 工具定义（来自 tools.json） */
    public static class ToolDef {
        public String name;
        public String description;
        public JSONObject inputSchema;
        public String execType = "script";    // "script" | "inline"
        public String execSrc;                // 脚本相对路径（script 类型）
        public String code;                   // 内联代码或已解析出的脚本内容（inline 类型）

        public ToolDef() {}
    }
}
