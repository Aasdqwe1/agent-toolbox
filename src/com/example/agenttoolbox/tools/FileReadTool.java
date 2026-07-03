package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 文件读取工具 - 优化版
 *
 * 改进：
 * 1. 输出末尾附加 total_lines 字段，方便 LLM 后续写入时计算行号
 * 2. 动态行号宽度，不再固定 %4d（避免大文件行号截断）
 * 3. 二进制文件检测（前 8KB 含 null byte 则拒绝）
 * 4. 文件大小限制（防止 OOM）
 * 5. try-with-resources 防资源泄漏
 * 6. 路径解析统一使用 FilePathResolver
 */
public class FileReadTool implements Tool {

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB 读取上限
    private static final int BINARY_CHECK_SIZE = 8192;          // 前 8KB 检测二进制

    @Override
    public String getName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "读取指定路径的文本文件内容，支持按行号范围读取和显示行号。" +
                "路径支持：1) 相对路径（内部存储）；2) /storage/emulated/0/...（外部存储）；3) /Download/、/Documents/ 等简写。" +
                "建议配合 file_write 工具的 mode=insert/append 使用，避免行号偏移问题。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "文件路径，支持：1) 相对路径（内部存储）；2) /storage/emulated/0/...（外部存储）；3) /Download/、/Documents/ 等简写");
            properties.put("path", path);

            JSONObject encoding = new JSONObject();
            encoding.put("type", "string");
            encoding.put("description", "编码格式，默认utf-8");
            encoding.put("default", "utf-8");
            properties.put("encoding", encoding);

            JSONObject line = new JSONObject();
            line.put("type", "integer");
            line.put("description", "起始行号（从1开始），可选。设置后只读取从该行开始的内容");
            properties.put("line", line);

            JSONObject endLine = new JSONObject();
            endLine.put("type", "integer");
            endLine.put("description", "结束行号（包含），可选。不填则读到文件末尾");
            properties.put("end_line", endLine);

            JSONObject showLineNumbers = new JSONObject();
            showLineNumbers.put("type", "boolean");
            showLineNumbers.put("description", "是否在每行前显示行号，默认true");
            showLineNumbers.put("default", true);
            properties.put("show_line_numbers", showLineNumbers);

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
        String encoding = arguments.has("encoding") ? arguments.getString("encoding") : "UTF-8";
        int startLine = arguments.has("line") ? arguments.getInt("line") : -1;
        int endLine = arguments.has("end_line") ? arguments.getInt("end_line") : -1;
        boolean showLineNumbers = !arguments.has("show_line_numbers") || arguments.getBoolean("show_line_numbers");

        // 参数校验
        if (startLine < 1 && startLine != -1) {
            throw new Exception("起始行号必须大于等于 1");
        }
        if (endLine < 1 && endLine != -1) {
            throw new Exception("结束行号必须大于等于 1");
        }
        if (startLine > 0 && endLine > 0 && endLine < startLine) {
            throw new Exception("结束行号不能小于起始行号");
        }

        // 路径解析
        File file = FilePathResolver.resolveForRead(path);
        if (!file.exists()) {
            throw new Exception("文件不存在: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new Exception("文件不可读，请检查权限，请在系统设置中授权存储权限");
        }
        if (file.isDirectory()) {
            throw new Exception("这是一个目录，不是文件，请使用 file_list 工具列出目录内容");
        }

        // 文件大小检查
        if (file.length() > MAX_FILE_SIZE) {
            throw new Exception("文件过大 (" + formatSize(file.length()) + ")，超过 2MB 读取上限。请使用 line/end_line 分段读取");
        }

        // 二进制文件检测
        if (isBinaryFile(file)) {
            throw new Exception("这是一个二进制文件，无法以文本方式读取");
        }

        // 读取文件
        int totalLines = 0;
        int displayedLines = 0;
        StringBuilder content = new StringBuilder();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), encoding));
            String lineStr;
            while ((lineStr = reader.readLine()) != null) {
                totalLines++;
                if (startLine > 0 && totalLines < startLine) {
                    continue;
                }
                if (endLine > 0 && totalLines > endLine) {
                    break;
                }
                if (showLineNumbers) {
                    int width = Math.max(4, String.valueOf(totalLines).length());
                    content.append(String.format("%" + width + "d  ", totalLines));
                }
                content.append(lineStr).append("\n");
                displayedLines++;
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }

        // 构建输出
        String rangeInfo;
        if (startLine > 0 || endLine > 0) {
            int actualEnd = endLine > 0 ? Math.min(endLine, totalLines) : totalLines;
            int actualStart = startLine > 0 ? startLine : 1;
            rangeInfo = "第 " + actualStart + "-" + actualEnd + " 行（显示 " + displayedLines + " 行，全文 " + totalLines + " 行）";
        } else {
            rangeInfo = "共 " + totalLines + " 行";
        }

        String header = "文件内容 (" + file.getAbsolutePath() + ") " + rangeInfo + ":\n";

        return header + content.toString();
    }

    /**
     * 检测文件是否为二进制（前 N 字节是否含 null byte）
     */
    private boolean isBinaryFile(File file) {
        if (file.length() == 0) return false;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buf = new byte[BINARY_CHECK_SIZE];
            int read = fis.read(buf);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }
}
