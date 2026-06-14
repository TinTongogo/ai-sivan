package com.icusu.sivan.infra.sandbox;

import com.icusu.sivan.core.sandbox.SandboxBackend;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 降级沙箱后端 — 无 OS 级沙箱时使用，仅依赖 ulimit。
 * <p>
 * ⚠️ 降级模式下没有网络隔离和文件系统隔离！
 * Python 脚本可以访问文件系统和网络。
 * 建议在生产环境安装 sandbox-exec（macOS 自带）或 bubblewrap（Linux）。
 */
@Slf4j
class FallbackSandboxBackend implements SandboxBackend {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 50 * 1024;

    /** 是否阻止执行（由 SandboxExecutor 根据 requireSandbox 设置）。 */
    private final boolean blocked;

    FallbackSandboxBackend() {
        this(false);
    }

    FallbackSandboxBackend(boolean blocked) {
        this.blocked = blocked;
        if (blocked) {
            log.error("╔══════════════════════════════════════════════════╗");
            log.error("║  安全沙箱不可用，已根据配置阻止 bash 执行        ║");
            log.error("║  请安装 bwrap (Linux) 或使用 macOS               ║");
            log.error("║  或设置 sivan.sandbox.require=false 以跳过      ║");
            log.error("╚══════════════════════════════════════════════════╝");
        } else {
            log.warn("╔══════════════════════════════════════════════════╗");
            log.warn("║  ⚠️ 无 OS 级沙箱，bash 以降级模式运行           ║");
            log.warn("║  ⚠️ 无网络隔离 + 无文件系统隔离                 ║");
            log.warn("║  建议: 安装 bwrap (Linux) 或使用 macOS            ║");
            log.warn("║  设置 sivan.sandbox.require=true 可阻止降级执行   ║");
            log.warn("╚══════════════════════════════════════════════════╝");
        }
    }

    @Override
    public String name() { return blocked ? "降级（已阻止）" : "ulimit (降级模式)"; }

    @Override
    public boolean available() { return !blocked; }

    @Override
    public String execute(String command, String projectPath) {
        if (blocked) {
            return "错误：安全沙箱不可用，bash 执行已被禁止（sivan.sandbox.require=true）";
        }
        String guarded = "ulimit -c 0 -f 1048576 -t 30 -v 2097152 -n 256 -u 32; " + command;
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", guarded);
        pb.directory(new File(projectPath));
        String result = ProcessRunner.execute(pb, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        // 每条命令输出首行追加降级提醒，确保用户在前端可见
        return "⚠️ 无 OS 沙箱隔离（降级模式），bash 命令不可访问网络。\n\n" + result;
    }
}
