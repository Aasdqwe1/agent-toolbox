package com.example.agenttoolbox.skills;

import android.content.Context;
import android.content.res.AssetManager;
import com.example.agenttoolbox.AppLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.agenttoolbox.tools.ToolManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 管理器 —— 发现并接入 skill。
 *
 * 来源：
 *   1) assets/skills/         APK 内置（打包即带）
 *   2) <externalFiles>/skills 设备外部运行时目录（用户免重编 APK 即可添加/热加载）
 *
 * 能力：
 *   - 解析 SKILL.md 前置元数据（最小解析，无 YAML 依赖）
 *   - 把 tools.json 定义的工具注册进 ToolManager（冲突则跳过，内置/APK 工具优先）
 *   - 生成技能摘要注入系统提示词
 *   - 经 SkillReadTool 按需读取 SKILL.md 正文 / references 知识（渐进式披露）
 */
public class SkillManager {

    private static final String TAG = "SkillManager";
    private static SkillManager instance;
    private Context context;
    private final List<Skill> skills = new ArrayList<>();
    private final Map<String, Skill> skillById = new HashMap<>();
    private final List<String> registeredToolNames = new ArrayList<>();

    private SkillManager() {}

    public static synchronized SkillManager getInstance() {
        if (instance == null) instance = new SkillManager();
        return instance;
    }

    public void init(Context ctx) {
        this.context = ctx.getApplicationContext();
        discover();
    }

    /** 清空并重新发现 + 注册（热加载） */
    public synchronized void reload() {
        // 移除上次注册进 ToolManager 的技能工具
        if (!registeredToolNames.isEmpty()) {
            ToolManager.getInstance().removeTools(new HashSet<>(registeredToolNames));
            registeredToolNames.clear();
        }
        skills.clear();
        skillById.clear();
        discover();
    }

    private void discover() {
        if (context == null) return;
        File extDir = context.getExternalFilesDir(null);
        File base = (extDir != null) ? new File(extDir, "skills") : null;
        if (base != null) {
            if (!base.exists()) base.mkdirs();
            AppLogger.i(TAG, "运行时技能目录: " + base.getAbsolutePath());
        } else {
            AppLogger.w(TAG, "无法获取外部存储，运行时技能不可用");
        }
        discoverAssets();
        if (base != null) discoverRuntime(base);
        AppLogger.i(TAG, "已加载 " + skills.size() + " 个技能，注册工具 " + registeredToolNames.size() + " 个");
        for (Skill s : skills) {
            AppLogger.i(TAG, "  技能: " + s.id + " (from=" + (s.fromAssets ? "assets" : "runtime") + ")");
        }
    }

    // ============ 发现：assets 内置 ============
    private void discoverAssets() {
        AssetManager am = context.getAssets();
        String[] dirs;
        try {
            dirs = am.list("skills");
        } catch (IOException e) {
            return;
        }
        if (dirs == null) return;
        for (String id : dirs) {
            if (id.isEmpty()) continue;
            try {
                Skill skill = buildSkillFromAssets(am, id);
                if (skill != null) addSkill(skill);
            } catch (Exception e) {
                AppLogger.w(TAG, "跳过 assets 技能 " + id + ": " + e.getMessage());
            }
        }
    }

    private Skill buildSkillFromAssets(AssetManager am, String id) throws IOException, JSONException {
        InputStream is = am.open("skills/" + id + "/SKILL.md");
        ParsedMd parsed = parseSkillMd(is);
        Skill skill = new Skill();
        skill.id = id;
        skill.name = parsed.fm.getOrDefault("name", id);
        skill.description = parsed.fm.getOrDefault("description", "");
        skill.whenToUse = parsed.fm.getOrDefault("when_to_use", "");
        skill.override = parsed.fm.getOrDefault("override", "");
        skill.body = parsed.body;
        skill.fromAssets = true;
        skill.dir = null;

        // references/
        try {
            String[] refs = am.list("skills/" + id + "/references");
            if (refs != null) {
                for (String r : refs) {
                    if (!r.endsWith(".md")) continue;
                    String content = readAsset(am, "skills/" + id + "/references/" + r);
                    if (content != null) {
                        skill.referenceNames.add(r);
                        skill.references.put(r, content);
                    }
                }
            }
        } catch (IOException ignore) { /* 无 references 目录 */ }

        // tools.json
        try {
            String tj = readAsset(am, "skills/" + id + "/tools.json");
            if (tj != null) parseToolsJson(skill, tj, true);
        } catch (IOException ignore) { /* 无工具 */ }

        return skill;
    }

    // ============ 发现：运行时外部目录 ============
    private void discoverRuntime(File base) {
        if (!base.exists() || !base.isDirectory()) {
            AppLogger.d(TAG, "运行时技能目录不存在: " + base.getAbsolutePath());
            return;
        }
        File[] entries = base.listFiles();
        if (entries == null || entries.length == 0) {
            AppLogger.d(TAG, "运行时技能目录为空: " + base.getAbsolutePath());
            return;
        }
        for (File d : entries) {
            try {
                Skill skill = null;
                if (d.isDirectory()) {
                    // 子目录模式：<skill-id>/SKILL.md
                    skill = buildSkillFromRuntime(d);
                } else if (d.isFile() && d.getName().endsWith(".md")) {
                    // 单文件模式：<skill-id>.md（直接放 .md 文件即可）
                    skill = buildSkillFromSingleFile(d);
                }
                if (skill != null) addSkill(skill);
            } catch (Exception e) {
                AppLogger.w(TAG, "跳过运行时技能 " + d.getName() + ": " + e.getMessage());
            }
        }
    }

    /** 从单文件构建 skill：文件名（不含 .md）为 id，解析 frontmatter */
    private Skill buildSkillFromSingleFile(File f) throws IOException {
        String id = f.getName();
        if (id.endsWith(".md")) id = id.substring(0, id.length() - ".md".length());
        ParsedMd parsed = parseSkillMd(f);
        Skill skill = new Skill();
        skill.id = id;
        skill.name = parsed.fm.getOrDefault("name", id);
        skill.description = parsed.fm.getOrDefault("description", "");
        skill.whenToUse = parsed.fm.getOrDefault("when_to_use", "");
        skill.override = parsed.fm.getOrDefault("override", "");
        skill.body = parsed.body;
        skill.fromAssets = false;
        skill.dir = f.getParentFile();
        return skill;
    }

    private Skill buildSkillFromRuntime(File d) throws IOException, JSONException {
        File md = new File(d, "SKILL.md");
        if (!md.exists()) return null;
        ParsedMd parsed = parseSkillMd(md);
        Skill skill = new Skill();
        skill.id = d.getName();
        skill.name = parsed.fm.getOrDefault("name", d.getName());
        skill.description = parsed.fm.getOrDefault("description", "");
        skill.whenToUse = parsed.fm.getOrDefault("when_to_use", "");
        skill.override = parsed.fm.getOrDefault("override", "");
        skill.body = parsed.body;
        skill.fromAssets = false;
        skill.dir = d;

        File refDir = new File(d, "references");
        if (refDir.isDirectory()) {
            File[] rfs = refDir.listFiles();
            if (rfs != null) {
                for (File rf : rfs) {
                    if (rf.isFile() && rf.getName().endsWith(".md")) {
                        String content = readFile(rf);
                        skill.referenceNames.add(rf.getName());
                        skill.references.put(rf.getName(), content);
                    }
                }
            }
        }

        File tj = new File(d, "tools.json");
        if (tj.isFile()) parseToolsJson(skill, readFile(tj), false);
        return skill;
    }

    // ============ tools.json 解析 ============
    private void parseToolsJson(Skill skill, String json, boolean fromAssets) throws JSONException {
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Skill.ToolDef td = new Skill.ToolDef();
            td.name = o.optString("name", "");
            td.description = o.optString("description", "");
            td.inputSchema = o.optJSONObject("inputSchema");
            if (td.inputSchema == null) td.inputSchema = new JSONObject();
            JSONObject exec = o.optJSONObject("exec");
            if (exec != null) {
                td.execType = exec.optString("type", "script");
                if ("inline".equals(td.execType)) {
                    td.code = exec.optString("code", "");
                } else {
                    td.execSrc = exec.optString("src", "");
                    // assets 内置脚本在注册时解析为 inline code，避免执行期再读 assets
                    if (fromAssets && !td.execSrc.isEmpty() && context != null) {
                        try {
                            String content = readAsset(context.getAssets(), "skills/" + skill.id + "/" + td.execSrc);
                            if (content != null) { td.code = content; td.execType = "inline"; }
                        } catch (IOException ignore) {}
                    }
                }
            }
            if (!td.name.isEmpty()) skill.tools.add(td);
        }
    }

    private void addSkill(Skill skill) {
        // 去重：相同 id 的 skill 只保留一份。
        // discover() 先扫 assets 再扫 runtime，若用户在 runtime 目录放了与内置同 id 的 skill
        // （或同时存在 文件夹模式 <id>/ 和 单文件模式 <id>.md），都会触发此处。
        // 策略：runtime 覆盖 assets（用户自定义优先）；同来源重复则保留已注册的。
        Skill existing = skillById.get(skill.id);
        if (existing != null) {
            if (existing.fromAssets && !skill.fromAssets) {
                // runtime 覆盖 assets：默认内置优先，runtime 需显式声明 override: true 才覆盖
                // 防止 runtime 目录下同名的旧/测试文件意外覆盖内置 skill
                if (!"true".equalsIgnoreCase(skill.override)) {
                    AppLogger.i(TAG, "技能 " + skill.id + " runtime 版本未声明 override:true，保留内置版本");
                    return;
                }
                skills.remove(existing);
                if (!existing.tools.isEmpty()) {
                    java.util.Set<String> oldToolNames = new HashSet<>();
                    for (Skill.ToolDef td : existing.tools) oldToolNames.add(td.name);
                    ToolManager.getInstance().removeTools(oldToolNames);
                    registeredToolNames.removeAll(oldToolNames);
                }
                AppLogger.i(TAG, "技能 " + skill.id + " 被 runtime 版本覆盖 (override:true)");
            } else {
                // 同来源重复或 assets 覆盖 runtime（后者理论不会发生，因 assets 先扫）
                // 跳过新 skill，保留已注册的，避免重复
                AppLogger.w(TAG, "技能 " + skill.id + " 重复，跳过 (existing from="
                        + (existing.fromAssets ? "assets" : "runtime")
                        + ", new from=" + (skill.fromAssets ? "assets" : "runtime") + ")");
                return;
            }
        }
        skills.add(skill);
        skillById.put(skill.id, skill);
        registerSkillTools(skill);
    }

    private void registerSkillTools(Skill skill) {
        for (Skill.ToolDef td : skill.tools) {
            // 冲突处理：同名工具已存在则跳过，保证内置/APK 工具优先
            if (ToolManager.getInstance().getTool(td.name) != null) {
                AppLogger.w(TAG, "工具名冲突，跳过技能工具: " + td.name);
                continue;
            }
            SkillTool st = new SkillTool(skill.id, td.name, td.description, td.inputSchema,
                    td.execType, td.execSrc, td.code, skill.dir, skill.fromAssets, context);
            ToolManager.getInstance().registerTool(st);
            registeredToolNames.add(td.name);
        }
    }

    // ============ 对外查询 ============
    public synchronized JSONArray getSkillSummaries() {
        JSONArray arr = new JSONArray();
        for (Skill s : skills) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("description", s.description);
                o.put("when_to_use", s.whenToUse);
                o.put("from", s.fromAssets ? "builtin" : "runtime");
                arr.put(o);
            } catch (JSONException ignore) {}
        }
        return arr;
    }

    public synchronized Skill getSkill(String id) {
        return skillById.get(id);
    }

    /** 返回运行时技能目录的绝对路径，用于告知用户技能安装位置 */
    public synchronized String getRuntimeSkillsPath() {
        if (context == null) return "（Context 不可用）";
        File extDir = context.getExternalFilesDir(null);
        if (extDir != null) return new File(extDir, "skills").getAbsolutePath();
        File intDir = context.getFilesDir();
        if (intDir != null) return new File(intDir, "skills").getAbsolutePath();
        return "（无法获取存储路径）";
    }

    /** 供 SkillReadTool 使用：返回 SKILL.md 正文，或 references 下某文件内容 */
    public synchronized String readSkill(String id, String reference) {
        Skill s = skillById.get(id);
        if (s == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("技能不存在: ").append(id).append("\n\n当前已加载的技能:\n");
            for (Skill sk : skills) {
                sb.append("  - ").append(sk.id).append(" (").append(sk.name).append(")");
                if (sk.whenToUse != null && !sk.whenToUse.isEmpty()) sb.append(": ").append(sk.whenToUse);
                sb.append("\n");
            }
            if (skills.isEmpty()) sb.append("  (无)");
            return sb.toString();
        }
        if (reference != null && !reference.trim().isEmpty()) {
            String c = s.references.get(reference.trim());
            return (c != null) ? c : "未找到参考文件: " + reference;
        }
        return s.body != null ? s.body : "(无正文)";
    }

    // ============ 解析工具 ============
    /** 最小 frontmatter 解析：首行 --- 开始，下一个 --- 结束；逐行 key: value；其余为正文 */
    private static ParsedMd parseSkillMd(InputStream is) throws IOException {
        return parseSkillMd(new BufferedReader(new InputStreamReader(is, "UTF-8")));
    }

    private static ParsedMd parseSkillMd(File f) throws IOException {
        return parseSkillMd(new BufferedReader(new java.io.FileReader(f)));
    }

    private static ParsedMd parseSkillMd(BufferedReader br) throws IOException {
        ParsedMd p = new ParsedMd();
        StringBuilder b = new StringBuilder();
        String line = br.readLine();
        if (line == null) return p;
        boolean inFm = line.trim().equals("---");
        if (!inFm) b.append(line).append("\n");
        List<String> fmLines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            if (inFm) {
                if (line.trim().equals("---")) { inFm = false; continue; }
                fmLines.add(line);
            } else {
                b.append(line).append("\n");
            }
        }
        for (int i = 0; i < fmLines.size(); i++) {
            String fl = fmLines.get(i);
            int idx = fl.indexOf(':');
            if (idx < 0) continue;
            String k = fl.substring(0, idx).trim();
            String v = fl.substring(idx + 1).trim();
            // YAML 块标量: key: | 或 key: > — 后续缩进行为多行内容，直到非缩进行或 ---
            if (v.equals("|") || v.equals(">")) {
                StringBuilder block = new StringBuilder();
                int j = i + 1;
                while (j < fmLines.size()) {
                    String bl = fmLines.get(j);
                    // 块内行必须缩进（以空格开头）；空行允许
                    if (bl.isEmpty() || bl.startsWith(" ") || bl.startsWith("\t")) {
                        block.append(bl).append("\n");
                        j++;
                    } else {
                        break;
                    }
                }
                v = block.toString().trim();
                i = j - 1; // 跳过已消费的行
            } else if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            if (!k.isEmpty()) p.fm.put(k, v);
        }
        p.body = trimRight(b.toString());
        return p;
    }

    private static String trimRight(String s) {
        int i = s.length() - 1;
        while (i >= 0 && (s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == ' ')) i--;
        return s.substring(0, i + 1);
    }

    private static String readAsset(AssetManager am, String path) throws IOException {
        try (InputStream is = am.open(path)) {
            return readStream(is);
        }
    }

    private static String readFile(File f) throws IOException {
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    private static class ParsedMd {
        Map<String, String> fm = new HashMap<>();
        String body = "";
    }
}
