package com.icusu.sivan.infra.sandbox;

import com.icusu.sivan.core.sandbox.SandboxBackend;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * macOS 沙箱后端 — 通过 {@code sandbox-exec} 实现 OS 级隔离。
 */
@Slf4j
class MacSandboxBackend implements SandboxBackend {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 50 * 1024;

    @Override
    public String name() { return "sandbox-exec (macOS)"; }

    @Override
    public boolean available() {
        try {
            Process p = new ProcessBuilder("sandbox-exec", "-n", "no-network", "/bin/echo", "ok")
                    .redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String execute(String command, String projectPath) {
        Path profilePath = null;
        try {
            String profile = SandboxProfile.forProject(projectPath).generate();
            profilePath = Files.writeString(
                    Files.createTempFile("sivan_sandbox_", ".sb"), profile);

            String guarded = "ulimit -c 0 -f 1048576 -t 30 -v 2097152 -n 256 -u 32; " + command;
            ProcessBuilder pb = new ProcessBuilder(
                    "sandbox-exec", "-f", profilePath.toString(), "/bin/bash", "-c", guarded);
            pb.directory(new File(projectPath));

            return ProcessRunner.execute(pb, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        } catch (Exception e) {
            log.warn("sandbox-exec 异常: {}", e.getMessage());
            return "错误：沙箱执行失败 - " + e.getMessage();
        } finally {
            if (profilePath != null) {
                try { Files.deleteIfExists(profilePath); } catch (Exception ignored) {}
            }
        }
    }
}
