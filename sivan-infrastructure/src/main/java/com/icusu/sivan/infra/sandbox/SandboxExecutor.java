package com.icusu.sivan.infra.sandbox;

import com.icusu.sivan.core.sandbox.SandboxBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 沙箱命令执行器。
 * <p>
 * 按平台自动选择沙箱后端：
 * <ul>
 *   <li>macOS → {@link MacSandboxBackend} (sandbox-exec)</li>
 *   <li>Linux → {@link LinuxSandboxBackend} (bubblewrap)</li>
 *   <li>其他 → {@link FallbackSandboxBackend} (ulimit)</li>
 * </ul>
 * <p>
 * 当 {@code sivan.sandbox.require=true} 且无 OS 级沙箱时，bash 执行将被阻止。
 */
@Slf4j
@Component
public class SandboxExecutor {

    private final SandboxBackend backend;

    public SandboxExecutor(@Value("${sivan.sandbox.require:false}") boolean requireSandbox) {
        this.backend = selectBackend(requireSandbox);
        log.info("SandboxExecutor 初始化: backend={}", backend.name());
    }

    private static SandboxBackend selectBackend(boolean requireSandbox) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac") || os.contains("darwin")) {
            MacSandboxBackend b = new MacSandboxBackend();
            if (b.available()) return b;
            log.warn("sandbox-exec 不可用，降级");
            return new FallbackSandboxBackend(requireSandbox);
        }

        if (os.contains("linux")) {
            LinuxSandboxBackend b = new LinuxSandboxBackend();
            if (b.available()) return b;
            log.warn("bwrap 不可用（安装: apt install bubblewrap / dnf install bubblewrap）");
            return new FallbackSandboxBackend(requireSandbox);
        }

        log.warn("未知平台 ({}), 使用降级模式", os);
        return new FallbackSandboxBackend(requireSandbox);
    }

    public String execute(String command, String projectPath) {
        return backend.execute(command, projectPath);
    }
}
