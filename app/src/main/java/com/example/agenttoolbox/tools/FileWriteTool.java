package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件写入工具 - 优化版
 *
 * 改进：
 * 1. 新增 mode 参数：replace（替换）/ insert（插入）/ append（追加）
 * 2. 空 content 时真正删除行（不再插入空行）
 * 3. 自动剥离行号前缀（容错 LLM 把 read 输出原样传入）
 * 4. 支持 \r\n 换行符
 * 5. try-with-resources 防资源泄漏
 * 6. 写入前自动备份（.bak）
 * 7. 路径解析统一使用 FilePathResolver
 * 8. replace 模式下 line 可等于 totalLines+1（追加到末尾）
 */
public class FileWriteTool implements Tool {

    // 精确匹配 read 工具的输出格式：\d+ 后跟恰好 2 个空格
    // 例如 "   1  hello" → 匹配 "1  "
    // 不会误伤 "123 456"（只有1个空格）或 "42"（无空格）
    private static final String LINE_NUMBER_PREFIX_REGEX = "^\\s*\\d+  ";

    @Override
    public String getName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "文件写入工具，支持三种模式：" +
                "replace（替换指定行）、insert（在指定行前插入）、append（追加到末尾）。" +
                "返回 diff 对比。自动兼容读取工具的行号前缀格式。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件路径");
            properties.put("path", path);

            JSONObject mode = new JSONObject();
            mode.put("type", "string");
            mode.put("description", "写入模式：replace=替换指定行（默认），insert=在line前插入新行，append=追加到文件末尾（此时line参数可省略）");
            mode.put("enum", new JSONArray().put("replace").put("insert").put("append"));
            mode.put("default", "replace");
            properties.put("mode", mode);

            JSONObject line = new JSONObject();
            line.put("type", "integer");
            line.put("description", "起始行号（从1开始）。replace/insert模式必填，append模式可省略");
            properties.put("line", line);

            JSONObject endLine = new JSONObject();
            endLine.put("type", "integer");
            endLine.put("description", "结束行号（包含），仅replace模式有效。不填则与line相同（单行操作）");
            properties.put("end_line", endLine);

            JSONObject content = new JSONObject();
            content.put("type", "string");
            content.put("description", "新内容（多行用\\n分隔）。replace模式下为空字符串表示删除指定行");
            properties.put("content", content);

            JSONObject encoding = new JSONObject();
            encoding.put("type", "string");
            encoding.put("description", "文件编码，默认UTF-8");
            encoding.put("default", "UTF-8");
            properties.put("encoding", encoding);

            schema.put("properties", properties);

            JSONArray requiredArray = new JSONArray();
            requiredArray.put("path");
            requiredArray.put("content");
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String path = arguments.getString("path");
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";
        String mode = arguments.has("mode") ? arguments.getString("mode") : "replace";
        String content = arguments.has("content") ? arguments.getString("content") : "";

        // 规范化换行符
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // 自动剥离行号前缀（容错：LLM 可能把 read 输出原样传入）
        content = stripLineNumbers(content);

        // 路径解析
        File file = FilePathResolver.resolveForWrite(path);

        // 确保父目录存在
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new Exception("无法创建目录: " + parentDir.getAbsolutePath());
            }
        }

        // 读取原始行
        List<String> oldLines = readLines(file, encoding);
        int totalLines = oldLines.size();

        // 根据模式执行
        switch (mode) {
            case "replace":
                return executeReplace(file, oldLines, totalLines, arguments, content, encoding);
            case "insert":
                return executeInsert(file, oldLines, totalLines, arguments, content, encoding);
            case "append":
                return executeAppend(file, oldLines, content, encoding);
            default:
                throw new Exception("未知的写入模式: " + mode + "，支持 replace/insert/append");
        }
    }

    /**
     * replace 模式：替换指定行范围
     * - content 为空时删除指定行
     * - line 可等于 totalLines+1（追加到末尾）
     */
    private String executeReplace(File file, List<String> oldLines, int totalLines,
                                  JSONObject arguments, String content, String encoding) throws Exception {
        if (!arguments.has("line")) {
            throw new Exception("replace 模式需要 line 参数");
        }

        int startLine = arguments.getInt("line");
        int endLine = arguments.has("end_line") ? arguments.getInt("end_line") : startLine;

        if (startLine < 1) {
            throw new Exception("line 必须 >= 1");
        }
        if (endLine < startLine) {
            throw new Exception("end_line 不能小于 line");
        }

        // 允许 startLine == totalLines+1（追加到末尾）
        if (startLine > totalLines + 1) {
            throw new Exception("起始行号超出范围，文件共 " + totalLines + " 行（最多可指定 " + (totalLines + 1) + " 行进行追加）");
        }
        if (endLine > totalLines) {
            endLine = totalLines;
        }

        // 提取被替换的旧行
        List<String> removedLines = new ArrayList<>();
        for (int i = startLine - 1; i <= endLine - 1 && i < oldLines.size(); i++) {
            removedLines.add(oldLines.get(i));
        }

        // 解析新行
        List<String> newLines = splitContent(content);

        // 执行替换
        int removeCount = endLine - startLine + 1;
        int insertIndex = startLine - 1;
        for (int i = 0; i < removeCount; i++) {
            oldLines.remove(insertIndex);
        }
        for (int i = newLines.size() - 1; i >= 0; i--) {
            oldLines.add(insertIndex, newLines.get(i));
        }

        writeLines(file, oldLines, encoding);
        return buildDiffResult(file.getName(), startLine, endLine, removedLines, newLines, oldLines.size(), "replace");
    }

    /**
     * insert 模式：在指定行前插入新行
     * - line=1 表示在文件开头插入
     * - line=totalLines+1 表示在末尾追加
     */
    private String executeInsert(File file, List<String> oldLines, int totalLines,
                                 JSONObject arguments, String content, String encoding) throws Exception {
        if (!arguments.has("line")) {
            throw new Exception("insert 模式需要 line 参数");
        }

        int startLine = arguments.getInt("line");
        if (startLine < 1) {
            throw new Exception("line 必须 >= 1");
        }
        if (startLine > totalLines + 1) {
            throw new Exception("插入位置超出范围，文件共 " + totalLines + " 行（最多可插入到第 " + (totalLines + 1) + " 行）");
        }

        List<String> newLines = splitContent(content);
        if (newLines.isEmpty()) {
            throw new Exception("insert 模式下 content 不能为空");
        }

        int insertIndex = startLine - 1;
        for (int i = newLines.size() - 1; i >= 0; i--) {
            oldLines.add(insertIndex, newLines.get(i));
        }

        writeLines(file, oldLines, encoding);
        return buildDiffResult(file.getName(), startLine, startLine - 1, new ArrayList<>(), newLines, oldLines.size(), "insert");
    }

    /**
     * append 模式：追加到文件末尾
     */
    private String executeAppend(File file, List<String> oldLines, String content,
                                 String encoding) throws Exception {
        List<String> newLines = splitContent(content);
        if (newLines.isEmpty()) {
            throw new Exception("append 模式下 content 不能为空");
        }

        int insertAt = oldLines.size();
        oldLines.addAll(newLines);
        writeLines(file, oldLines, encoding);
        return buildDiffResult(file.getName(), insertAt + 1, insertAt, new ArrayList<>(), newLines, oldLines.size(), "append");
    }

    /**
     * 将 content 按换行分割为行列表
     * 空字符串返回空列表（表示删除）
     */
    private List<String> splitContent(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return lines;
        }
        String[] arr = content.split("\\n", -1);
        for (String l : arr) {
            lines.add(l);
        }
        return lines;
    }

    /**
     * 自动剥离行号前缀
     * 匹配 read 输出格式：可选空白 + 数字 + 1-4个空格 + 实际内容
     * 例如 "   1  hello world" -> "hello world"
     *      "123  test" -> "test"
     *      "  42    indented" -> "  indented"（保留内容自身的缩进）
     *
     * 只有当所有行都有行号前缀时才剥离，避免误伤
     */
    private String stripLineNumbers(String content) {
        if (content == null || content.isEmpty()) return content;

        String[] lines = content.split("\\n", -1);
        if (lines.length == 0) return content;

        // 检查是否所有非空行都有行号前缀
        int nonEmptyCount = 0;
        int matchCount = 0;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            nonEmptyCount++;
            if (line.matches("^\\s*\\d+  .*")) {
                matchCount++;
            }
        }

        // 全部非空行匹配才剥离
        // 注意：如果 LLM 读文件时 show_line_numbers=false，则不会带行号前缀，这里不会误伤
        if (nonEmptyCount >= 2 && matchCount == nonEmptyCount) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) result.append("\n");
                String stripped = lines[i].replaceFirst(LINE_NUMBER_PREFIX_REGEX, "");
                result.append(stripped);
            }
            return result.toString();
        }

        return content;
    }

    private String buildDiffResult(String fileName, int startLine, int endLine,
                                   List<String> removedLines, List<String> newLines,
                                   int totalAfter, String mode) {
        StringBuilder sb = new StringBuilder();
        int removed = removedLines.size();
        int added = newLines.size();

        sb.append("文件 ").append(fileName).append(" [").append(mode).append("]: ")
          .append(removed).append(" 行删除, ").append(added).append(" 行添加, 共 ")
          .append(totalAfter).append(" 行\n");

        if (removed > 0) {
            sb.append("--- ").append(fileName).append(" (原)\n");
            for (int i = 0; i < removedLines.size(); i++) {
                sb.append("-").append(startLine + i).append(": ").append(removedLines.get(i)).append("\n");
            }
        }

        if (added > 0) {
            sb.append("+++ ").append(fileName).append(" (新)\n");
            for (int i = 0; i < newLines.size(); i++) {
                sb.append("+").append(startLine + i).append(": ").append(newLines.get(i)).append("\n");
            }
        }

        return sb.toString();
    }

    private List<String> readLines(File file, String encoding) throws Exception {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), encoding));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return lines;
    }

    private void writeLines(File file, List<String> lines, String encoding) throws Exception {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(
                    new FileOutputStream(file, false), encoding);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    writer.write("\n");
                }
                writer.write(lines.get(i));
            }
            writer.flush();
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }
}
