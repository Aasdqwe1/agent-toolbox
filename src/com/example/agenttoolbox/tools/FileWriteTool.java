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

public class FileWriteTool implements Tool {

    private static final String[] ALLOWED_DIRS = {
            "Download", "Documents", "Pictures", "DCIM", "Movies"
    };

    private static final String[] ALLOWED_SHORTHAND_DIRS = {
            "/Download/", "/Documents/", "/Pictures/", "/DCIM/", "/Movies/"
    };

    private static final String[] ALLOWED_EXTERNAL_PREFIXES = {
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Documents/",
            "/storage/emulated/0/Pictures/",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/Movies/",
            "/sdcard/Download/",
            "/sdcard/Documents/",
            "/sdcard/Pictures/",
            "/sdcard/DCIM/",
            "/sdcard/Movies/",
    };

    @Override
    public String getName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "文件写入工具，支持多种操作模式：全量写入(write)、按行插入(insert)、按行替换(replace)、按行删除(delete)、追加行(append_line)、前置行(prepend_line)。路径支持：1) 相对路径（基于应用内部存储）；2) /storage/emulated/0/Download/xxx.txt（外部公共存储）；3) /Download/、/Documents/、/Pictures/、/DCIM/、/Movies/ 等简写路径";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件路径：1) 相对路径（内部存储）；2) /storage/emulated/0/Download/...（外部存储）；3) /Download/...（外部下载目录简写）；4) /Documents/...、/Pictures/...（其他简写）");
            properties.put("path", path);

            JSONObject mode = new JSONObject();
            mode.put("type", "string");
            mode.put("description", "操作模式：write(全量写入/覆盖/追加，默认)、insert(在指定行前插入)、replace(替换指定行范围)、delete(删除指定行范围)、append_line(末尾追加一行)、prepend_line(开头插入一行)");
            mode.put("default", "write");
            properties.put("mode", mode);

            JSONObject content = new JSONObject();
            content.put("type", "string");
            content.put("description", "待写入的文本内容（write/insert/replace/append_line/prepend_line 模式必填，delete 模式可省略）");
            properties.put("content", content);

            JSONObject line = new JSONObject();
            line.put("type", "integer");
            line.put("description", "目标行号（从1开始）。insert 模式：在该行前插入；replace 模式：起始行；delete 模式：起始行");
            properties.put("line", line);

            JSONObject endLine = new JSONObject();
            endLine.put("type", "integer");
            endLine.put("description", "结束行号（包含）。replace/delete 模式可选，不填则与 line 相同（单行操作）");
            properties.put("end_line", endLine);

            JSONObject append = new JSONObject();
            append.put("type", "boolean");
            append.put("description", "仅 write 模式有效，是否追加模式，默认false覆盖");
            append.put("default", false);
            properties.put("append", append);

            JSONObject encoding = new JSONObject();
            encoding.put("type", "string");
            encoding.put("description", "文件编码格式，默认UTF-8");
            encoding.put("default", "UTF-8");
            properties.put("encoding", encoding);

            schema.put("properties", properties);

            String[] required = {"path"};
            JSONArray requiredArray = new JSONArray();
            for (String r : required) requiredArray.put(r);
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String path = arguments.getString("path");
        String mode = arguments.has("mode") ? arguments.getString("mode") : "write";
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";

        if (path.contains("..")) {
            throw new Exception("不允许的路径格式，禁止使用 '..'");
        }

        File file = resolveFile(path);

        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new Exception("无法创建目录: " + parentDir.getAbsolutePath());
            }
        }

        String content = arguments.has("content") ? arguments.getString("content") : "";
        int line = arguments.has("line") ? arguments.getInt("line") : -1;
        int endLine = arguments.has("end_line") ? arguments.getInt("end_line") : -1;
        boolean append = arguments.has("append") && arguments.getBoolean("append");

        String result;
        switch (mode.toLowerCase()) {
            case "write":
                result = writeFile(file, content, append, encoding);
                break;
            case "insert":
                if (line <= 0) {
                    throw new Exception("insert 模式必须指定 line 参数（行号从1开始）");
                }
                result = insertLines(file, line, content, encoding);
                break;
            case "replace":
                if (line <= 0) {
                    throw new Exception("replace 模式必须指定 line 参数（起始行号，从1开始）");
                }
                if (endLine <= 0) {
                    endLine = line;
                }
                if (endLine < line) {
                    throw new Exception("end_line 不能小于 line");
                }
                result = replaceLines(file, line, endLine, content, encoding);
                break;
            case "delete":
                if (line <= 0) {
                    throw new Exception("delete 模式必须指定 line 参数（起始行号，从1开始）");
                }
                if (endLine <= 0) {
                    endLine = line;
                }
                if (endLine < line) {
                    throw new Exception("end_line 不能小于 line");
                }
                result = deleteLines(file, line, endLine, encoding);
                break;
            case "append_line":
                result = appendLine(file, content, encoding);
                break;
            case "prepend_line":
                result = prependLine(file, content, encoding);
                break;
            default:
                throw new Exception("不支持的操作模式: " + mode + "，支持的模式: write, insert, replace, delete, append_line, prepend_line");
        }

        return result;
    }

    private File resolveFile(String path) throws Exception {
        File file;

        if (isExternalStoragePath(path)) {
            if (!isAllowedExternalPath(path)) {
                throw new Exception("不允许的外部存储路径，仅支持 Download/Documents/Pictures/DCIM/Movies 等公共目录");
            }
            file = new File(path);
        } else if (isShorthandExternalPath(path)) {
            String relativePath = path.substring(1);
            String fullPath = new File(getExternalStorageDir(), relativePath).getAbsolutePath();
            if (!isAllowedExternalPath(fullPath)) {
                throw new Exception("不允许的外部存储路径，仅支持 Download/Documents/Pictures/DCIM/Movies 等公共目录");
            }
            file = new File(fullPath);
        } else if (path.startsWith("/")) {
            throw new Exception("不允许的路径格式，支持: 1) 相对路径（内部存储）; 2) /storage/emulated/0/Download/...（外部存储）; 3) /Download/...（简写）");
        } else {
            file = new File(getBaseDir(), path);
        }

        return file;
    }

    private String writeFile(File file, String content, boolean append, String encoding) throws Exception {
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, append), encoding);
        writer.write(content);
        writer.close();

        String mode = append ? "追加写入" : "写入";
        return "文件" + mode + "成功: " + file.getAbsolutePath() + " (" + content.length() + " 字符)";
    }

    private String insertLines(File file, int insertBeforeLine, String content, String encoding) throws Exception {
        List<String> lines = readLines(file, encoding);
        int totalLines = lines.size();

        if (insertBeforeLine > totalLines + 1) {
            throw new Exception("行号超出范围，文件共 " + totalLines + " 行，insert 行号最大为 " + (totalLines + 1));
        }
        if (insertBeforeLine < 1) {
            throw new Exception("行号必须大于等于 1");
        }

        String[] newLines = content.split("\\n", -1);
        int insertIndex = insertBeforeLine - 1;

        for (int i = newLines.length - 1; i >= 0; i--) {
            lines.add(insertIndex, newLines[i]);
        }

        writeLines(file, lines, encoding);

        return "插入成功: 在第 " + insertBeforeLine + " 行前插入了 " + newLines.length + " 行，文件共 " + lines.size() + " 行 (" + file.getAbsolutePath() + ")";
    }

    private String replaceLines(File file, int startLine, int endLine, String content, String encoding) throws Exception {
        List<String> lines = readLines(file, encoding);
        int totalLines = lines.size();

        if (startLine > totalLines) {
            throw new Exception("起始行号超出范围，文件共 " + totalLines + " 行");
        }
        if (endLine > totalLines) {
            endLine = totalLines;
        }
        if (startLine < 1 || endLine < 1) {
            throw new Exception("行号必须大于等于 1");
        }

        String[] newLines = content.split("\\n", -1);
        int removeCount = endLine - startLine + 1;
        int insertIndex = startLine - 1;

        for (int i = 0; i < removeCount; i++) {
            lines.remove(insertIndex);
        }

        for (int i = newLines.length - 1; i >= 0; i--) {
            lines.add(insertIndex, newLines[i]);
        }

        writeLines(file, lines, encoding);

        return "替换成功: 第 " + startLine + "-" + endLine + " 行（" + removeCount + " 行）被替换为 " + newLines.length + " 行，文件共 " + lines.size() + " 行 (" + file.getAbsolutePath() + ")";
    }

    private String deleteLines(File file, int startLine, int endLine, String encoding) throws Exception {
        List<String> lines = readLines(file, encoding);
        int totalLines = lines.size();

        if (startLine > totalLines) {
            throw new Exception("起始行号超出范围，文件共 " + totalLines + " 行");
        }
        if (endLine > totalLines) {
            endLine = totalLines;
        }
        if (startLine < 1 || endLine < 1) {
            throw new Exception("行号必须大于等于 1");
        }

        int removeCount = endLine - startLine + 1;
        int removeIndex = startLine - 1;

        for (int i = 0; i < removeCount; i++) {
            lines.remove(removeIndex);
        }

        writeLines(file, lines, encoding);

        return "删除成功: 删除第 " + startLine + "-" + endLine + " 行（" + removeCount + " 行），文件共 " + lines.size() + " 行 (" + file.getAbsolutePath() + ")";
    }

    private String appendLine(File file, String content, String encoding) throws Exception {
        List<String> lines;
        if (file.exists()) {
            lines = readLines(file, encoding);
        } else {
            lines = new ArrayList<>();
        }

        String[] newLines = content.split("\\n", -1);
        for (String line : newLines) {
            lines.add(line);
        }

        writeLines(file, lines, encoding);

        return "追加成功: 在末尾追加了 " + newLines.length + " 行，文件共 " + lines.size() + " 行 (" + file.getAbsolutePath() + ")";
    }

    private String prependLine(File file, String content, String encoding) throws Exception {
        List<String> lines;
        if (file.exists()) {
            lines = readLines(file, encoding);
        } else {
            lines = new ArrayList<>();
        }

        String[] newLines = content.split("\\n", -1);
        for (int i = newLines.length - 1; i >= 0; i--) {
            lines.add(0, newLines[i]);
        }

        writeLines(file, lines, encoding);

        return "前置成功: 在开头插入了 " + newLines.length + " 行，文件共 " + lines.size() + " 行 (" + file.getAbsolutePath() + ")";
    }

    private List<String> readLines(File file, String encoding) throws Exception {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        reader.close();

        return lines;
    }

    private void writeLines(File file, List<String> lines, String encoding) throws Exception {
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, false), encoding);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                writer.write("\n");
            }
            writer.write(lines.get(i));
        }
        writer.close();
    }

    private boolean isExternalStoragePath(String path) {
        return path.startsWith("/storage/emulated/0/") || path.startsWith("/sdcard/");
    }

    private boolean isShorthandExternalPath(String path) {
        for (String shorthand : ALLOWED_SHORTHAND_DIRS) {
            if (path.startsWith(shorthand)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedExternalPath(String path) {
        for (String prefix : ALLOWED_EXTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private File getBaseDir() {
        return new File("/data/data/com.example.agenttoolbox/files");
    }

    private File getExternalStorageDir() {
        return new File("/storage/emulated/0");
    }

}
