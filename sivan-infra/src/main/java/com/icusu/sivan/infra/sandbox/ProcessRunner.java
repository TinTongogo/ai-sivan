package com.icusu.sivan.infra.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行工具 — 跨沙箱后端共享。
 */
final class ProcessRunner {

    private ProcessRunner() {}

    record Result(int exitCode, String stdout, String stderr) {}

    static String execute(ProcessBuilder pb, int timeoutSeconds, int maxOutputBytes) {
        try {
            Result r = run(pb, timeoutSeconds);
            return format(r, maxOutputBytes);
        } catch (IOException e) {
            return "错误：命令执行失败 - " + e.getMessage();
        }
    }

    static Result run(ProcessBuilder pb, int timeoutSeconds) throws IOException {
        Process process = pb.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Thread outReader = new Thread(() -> {
            try (var in = process.getInputStream()) { in.transferTo(stdout); }
            catch (IOException ignored) {}
        });
        Thread errReader = new Thread(() -> {
            try (var in = process.getErrorStream()) { in.transferTo(stderr); }
            catch (IOException ignored) {}
        });
        outReader.start();
        errReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            outReader.interrupt();
            errReader.interrupt();
            return new Result(-1, "", "命令执行被中断");
        }

        if (!finished) {
            killTree(process);
            outReader.interrupt();
            errReader.interrupt();
            return new Result(-1, "", "命令执行超时（" + timeoutSeconds + "秒）");
        }

        try {
            outReader.join(1000);
            errReader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new Result(
                process.exitValue(),
                stdout.toString(Charset.defaultCharset()),
                stderr.toString(Charset.defaultCharset()));
    }

    private static void killTree(Process process) {
        try {
            var handle = process.toHandle();
            handle.descendants().forEach(ph -> {
                try { ph.destroyForcibly(); } catch (Exception ignored) {}
            });
            handle.destroyForcibly();
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    private static String format(Result r, int maxBytes) {
        StringBuilder out = new StringBuilder();
        if (!r.stdout.isEmpty()) {
            out.append(truncate(r.stdout, maxBytes));
        }
        if (!r.stderr.isEmpty()) {
            if (!out.isEmpty()) out.append("\n");
            out.append("[stderr]\n").append(truncate(r.stderr, maxBytes / 2));
        }
        if (out.isEmpty()) out.append("(无输出)");
        if (r.exitCode != 0) out.append("\n[退出码: ").append(r.exitCode).append("]");
        return out.toString();
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(Charset.defaultCharset());
        if (bytes.length <= maxBytes) return s;
        int len = maxBytes;
        while (len > 0 && (bytes[len] & 0xC0) == 0x80) len--;
        return new String(bytes, 0, len, Charset.defaultCharset())
                + "\n...(输出已截断，共 " + s.length() + " 字符)";
    }
}
