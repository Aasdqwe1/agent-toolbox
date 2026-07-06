package com.example.agenttoolbox.tools;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 进程执行器 - 统一处理 shell 命令执行
 */
public class ProcessRunner {

    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

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
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
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

        Thread stdoutThread = new Thread(new Runnable() {
            public void run() {
                try {
                    truncated[0] = readStream(process.getInputStream(), stdoutBuf) || truncated[0];
                } catch (Exception ignored) {
                }
            }
        });

        Thread stderrThread = new Thread(new Runnable() {
            public void run() {
                try {
                    truncated[0] = readStream(process.getErrorStream(), stderrBuf) || truncated[0];
                } catch (Exception ignored) {
                }
            }
        });

        stdoutThread.start();
        stderrThread.start();

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

        stdoutThread.join(5000);
        stderrThread.join(5000);

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
        int ch;
        while ((ch = is.read()) != -1) {
            if (buf.length() >= MAX_OUTPUT_CHARS) {
                buf.append("\n... [输出截断，超过 64KB 限制]");
                while (is.read() != -1) {
                }
                return true;
            }
            buf.append((char) ch);
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
