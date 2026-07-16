package com.example.agenttoolbox.tools;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程执行器 - 统一处理 shell 命令执行
 */
public class ProcessRunner {

    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean truncated;
        public final boolean timedOut;

        public Result(int exitCode, String stdout, String stderr, boolean truncated, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.truncated = truncated;
            this.timedOut = timedOut;
        }
    }

    public static Result exec(String[] cmd, int timeoutSeconds) throws Exception {
        return exec(cmd, null, timeoutSeconds);
    }

    public static Result exec(String[] cmd, java.util.Map<String, String> env, int timeoutSeconds) throws Exception {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (env != null) {
                pb.environment().putAll(env);
            }
            process = pb.start();
            return waitForAndRead(process, timeoutSeconds);
        } finally {
            destroyProcess(process);
        }
    }

    public static Result execShell(String command, int timeoutSeconds) throws Exception {
        return exec(new String[]{"sh", "-c", command}, timeoutSeconds);
    }

    private static Result waitForAndRead(final Process process, int timeoutSeconds) throws Exception {
        final StringBuilder stdoutBuf = new StringBuilder();
        final StringBuilder stderrBuf = new StringBuilder();
        final boolean[] truncated = {false};
        final CountDownLatch doneLatch = new CountDownLatch(2);

        threadPool.execute(new Runnable() {
            public void run() {
                try {
                    truncated[0] = readStream(process.getInputStream(), stdoutBuf) || truncated[0];
                } catch (Exception ignored) {
                } finally { doneLatch.countDown(); }
            }
        });

        threadPool.execute(new Runnable() {
            public void run() {
                try {
                    truncated[0] = readStream(process.getErrorStream(), stderrBuf) || truncated[0];
                } catch (Exception ignored) {
                } finally { doneLatch.countDown(); }
            }
        });

        // 等待进程完成（轮询方式，兼容所有 Android 版本）
        boolean finished = false;
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                finished = true;
                break;
            } catch (IllegalThreadStateException e) {
                // 进程还在运行
                Thread.sleep(200);
            }
        }

        // 等待输出读完（最多5秒）
        try { doneLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignored) {}

        int exitCode;
        boolean timedOut = false;

        if (finished) {
            exitCode = process.exitValue();
        } else {
            timedOut = true;
            process.destroy();
            exitCode = -1;
        }

        return new Result(exitCode, stdoutBuf.toString(), stderrBuf.toString(), truncated[0], timedOut);
    }

    private static boolean readStream(InputStream is, StringBuilder buf) throws Exception {
        java.io.Reader reader = new java.io.InputStreamReader(is, "UTF-8");
        char[] buffer = new char[4096];
        int len;
        while ((len = reader.read(buffer)) != -1) {
            if (buf.length() + len > MAX_OUTPUT_CHARS) {
                int allowed = MAX_OUTPUT_CHARS - buf.length();
                if (allowed > 0) buf.append(buffer, 0, allowed);
                buf.append("\n... [输出截断，超过 64KB 限制]");
                // 消耗剩余数据
                while (reader.read(buffer) != -1) {}
                return true;
            }
            buf.append(buffer, 0, len);
        }
        return false;
    }

    private static void destroyProcess(Process process) {
        if (process == null) return;
        try {
            try {
                process.exitValue();
            } catch (IllegalThreadStateException e) {
                process.destroy();
            }
        } catch (Exception ignored) {
        }
    }
}
