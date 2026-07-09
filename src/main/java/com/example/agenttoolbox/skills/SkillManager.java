package com.example.agenttoolbox.skills;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        discoverAssets();
        discoverRuntime();
        Log.i(TAG, "已加载 " + skills.size() + " 个技能，注册工具 " + registeredToolNames.size() + " 个");
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
                Log.w(TAG, "跳过 assets 技能 " + id + ": " + e.getMessage());
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
    private void discoverRuntime() {
        File base = new File(context.getExternalFilesDir(null), "skills");
        if (!base.exists() || !base.isDirectory()) return;
        File[] dirs = base.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            try {
                Skill skill = buildSkillFromRuntime(d);
                if (skill != null) addSkill(skill);
            } catch (Exception e) {
                Log.w(TAG, "跳过运行时技能 " + d.getName() + ": " + e.getMessage());
            }
        }
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
        skills.add(skill);
        skillById.put(skill.id, skill);
        registerSkillTools(skill);
    }

    private void registerSkillTools(Skill skill) {
        for (Skill.ToolDef td : skill.tools) {
            // 冲突处理：同名工具已存在则跳过，保证内置/APK 工具优先
            if (ToolManager.getInstance().getTool(td.name) != null) {
                Log.w(TAG, "工具名冲突，跳过技能工具: " + td.name);
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

    /** 供 SkillReadTool 使用：返回 SKILL.md 正文，或 references 下某文件内容 */
    public synchronized String readSkill(String id, String reference) {
        Skill s = skillById.get(id);
        if (s == null) return "技能不存在: " + id;
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
        String line = br.readLine();
        if (line == null) return p;
        boolean inFm = line.trim().equals("---");
        if (!inFm) p.body.append(line).append("\n");
        List<String> fmLines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            if (inFm) {
                if (line.trim().equals("---")) { inFm = false; continue; }
                fmLines.add(line);
            } else {
                p.body.append(line).append("\n");
            }
        }
        for (String fl : fmLines) {
            int idx = fl.indexOf(':');
            if (idx < 0) continue;
            String k = fl.substring(0, idx).trim();
            String v = fl.substring(idx + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            if (!k.isEmpty()) p.fm.put(k, v);
        }
        p.body = trimRight(p.body.toString());
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
        StringBuilder body = new StringBuilder();
    }
}
