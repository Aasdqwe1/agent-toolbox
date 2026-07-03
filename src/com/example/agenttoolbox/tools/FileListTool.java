package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件列表工具 - 优化版
 */
public class FileListTool implements Tool {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    public String getName() {
        return "file_list";
    }

    @Override
    public String getDescription() {
        return "列出指定目录下的文件和子目录。支持按名称/大小/修改时间排序，可选显示隐藏文件。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "目录路径");
            properties.put("path", path);

            JSONObject sort = new JSONObject();
            sort.put("type", "string");
            sort.put("description", "排序方式：name=按名称（默认），size=按文件大小，modified=按修改时间");
            sort.put("enum", new JSONArray().put("name").put("size").put("modified"));
            sort.put("default", "name");
            properties.put("sort", sort);

            JSONObject showHidden = new JSONObject();
            showHidden.put("type", "boolean");
            showHidden.put("description", "是否显示隐藏文件（以.开头），默认false");
            showHidden.put("default", false);
            properties.put("show_hidden", showHidden);

            schema.put("properties", properties);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String path = arguments.optString("path", "");
        String sort = arguments.optString("sort", "name");
        boolean showHidden = arguments.optBoolean("show_hidden", false);

        File dir = FilePathResolver.resolveForDir(path.isEmpty() ? null : path);

        if (!dir.exists()) {
            throw new Exception("目录不存在: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new Exception("这不是一个目录: " + dir.getAbsolutePath());
        }
        if (!dir.canRead()) {
            throw new Exception("目录不可读，请在系统设置中授权存储权限: " + dir.getAbsolutePath());
        }

        File[] files = dir.listFiles();
        if (files == null) {
            throw new Exception("无法读取目录内容，请检查权限: " + dir.getAbsolutePath());
        }

        // 过滤隐藏文件（不使用 Stream API）
        if (!showHidden) {
            List<File> visible = new ArrayList<File>();
            for (File f : files) {
                if (!f.getName().startsWith(".")) {
                    visible.add(f);
                }
            }
            files = visible.toArray(new File[visible.size()]);
        }

        // 排序
        sortFiles(files, sort);

        // 构建输出
        StringBuilder result = new StringBuilder();
        result.append("目录内容 (").append(dir.getAbsolutePath()).append("):\n");
        result.append("共 ").append(files.length).append(" 个项目");
        if (!showHidden) {
            result.append("（已隐藏 dotfiles）");
        }
        result.append("\n\n");

        result.append(String.format("%-10s %-12s %-20s %s\n", "类型", "大小", "修改时间", "名称"));
        result.append(String.format("%-10s %-12s %-20s %s\n", "----", "----", "--------", "----"));

        for (File file : files) {
            String type = file.isDirectory() ? "[目录]" : "[文件]";
            String size = file.isDirectory() ? "-" : formatFileSize(file.length());
            String modified = DATE_FMT.format(new Date(file.lastModified()));
            String name = file.getName();
            if (file.isDirectory()) {
                name += "/";
            }
            result.append(String.format("%-10s %-12s %-20s %s\n", type, size, modified, name));
        }

        return result.toString();
    }

    private void sortFiles(File[] files, String sort) {
        Comparator<File> comparator;

        if ("size".equals(sort)) {
            comparator = new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return Long.compare(a.length(), b.length());
                }
            };
        } else if ("modified".equals(sort)) {
            comparator = new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            };
        } else {
            comparator = new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
        }

        Arrays.sort(files, comparator);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}
