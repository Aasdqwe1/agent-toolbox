package com.example.agenttoolbox.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

/**
 * 简易 Tar 解析器
 *
 * 只实现最基本的解包功能，支持：
 * - 普通文件（type='0' 或 '\0'）
 * - 目录（type='5'）
 * - 长文件名（GNU ././@LongLink，type='L'）
 *
 * 不支持：pax header、硬链接、符号链接等高级特性
 * （Python Android 构建包通常只用基本格式）
 */
public class TarExtractor {

    private static final int BLOCK_SIZE = 512;

    /**
     * 从 tar 流中解压所有文件到目标目录
     */
    public static void extract(InputStream tarStream, File destDir) throws IOException {
        byte[] header = new byte[BLOCK_SIZE];
        String longName = null;

        while (true) {
            // 读取 512 字节 header
            int read = readFully(tarStream, header);
            if (read < BLOCK_SIZE) break;

            // 检查是否全零（tar 结束标记）
            if (isAllZero(header)) break;

            // 解析文件名（优先使用长文件名）
            String name = longName != null ? longName : parseString(header, 0, 100);
            longName = null;

            // 解析文件大小
            long size = parseOctal(header, 124, 12);

            // 解析类型
            char type = (char) header[156];

            // 解析路径前缀（ustar 格式）
            if (type != 0 && header[257] == 'u' && header[258] == 's') {
                String prefix = parseString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
            }

            // 处理长文件名
            if (type == 'L') {
                // GNU 长文件名：内容在下一个 header 之前
                longName = readString(tarStream, (int) size);
                skipPadding(tarStream, size);
                continue;
            }

            // 清理路径
            name = cleanPath(name);
            if (name.isEmpty()) continue;

            File target = new File(destDir, name);

            switch (type) {
                case '0':   // 普通文件
                case '\0':  // 有些 tar 用 null 代替 '0'
                case '7':   // 高性能文件
                    extractFile(tarStream, target, size);
                    break;

                case '5':   // 目录
                    target.mkdirs();
                    break;

                case '1':   // 硬链接 - 跳过
                case '2':   // 符号链接 - 跳过
                    skipPadding(tarStream, size);
                    break;

                default:
                    // 未知类型，跳过数据块
                    skipPadding(tarStream, size);
                    break;
            }
        }
    }

    private static void extractFile(InputStream in, File target, long size) throws IOException {
        // 确保父目录存在
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        FileOutputStream fos = new FileOutputStream(target);
        try {
            byte[] buf = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, toRead);
                if (n <= 0) break;
                fos.write(buf, 0, n);
                remaining -= n;
            }
            fos.flush();
        } finally {
            fos.close();
        }

        // 跳过 padding（tar 数据按 512 字节对齐）
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long dataSize) throws IOException {
        long remainder = dataSize % BLOCK_SIZE;
        if (remainder > 0) {
            long skip = BLOCK_SIZE - remainder;
            long skipped = 0;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) {
                    // skip 不可靠，手动读
                    if (in.read() == -1) break;
                    skipped++;
                } else {
                    skipped += n;
                }
            }
        }
    }

    private static String readString(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        int read = readFully(in, buf);
        // 去掉末尾 null
        int end = 0;
        while (end < read && buf[end] != 0) end++;
        return new String(buf, 0, end, "UTF-8");
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    private static String parseString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, header.length);
        while (end < limit && header[end] != 0) end++;
        return new String(header, offset, end - offset);
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String s = parseString(header, offset, length).trim();
        if (s.isEmpty()) return 0;
        try {
            // tar 八进制格式可能有前导空格或 NUL
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String cleanPath(String path) {
        // 移除前导 /
        while (path.startsWith("/")) path = path.substring(1);
        // 处理 ./
        while (path.startsWith("./")) path = path.substring(2);
        return path;
    }
}
