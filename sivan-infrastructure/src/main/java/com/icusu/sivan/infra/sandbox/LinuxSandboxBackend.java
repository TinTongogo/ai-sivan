package com.icusu.sivan.infra.sandbox;

import com.icusu.sivan.core.sandbox.SandboxBackend;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux 沙箱后端 — 通过 {@code bubblewrap (bwrap)} 实现 OS 级隔离。
 * <p>
 * 安装：{@code apt install bubblewrap} / {@code dnf install bubblewrap}。
 */
@Slf4j
class LinuxSandboxBackend implements SandboxBackend {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 50 * 1024;

    private static final String[] RO_DIRS = {
            "/usr", "/lib", "/lib64", "/bin", "/sbin", "/etc", "/opt"
    };

    @Override
    public String name() { return "bubblewrap (Linux)"; }

    @Override
    public boolean available() {
        try {
            Process p = new ProcessBuilder("bwrap", "--version")
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            p.destroyForcibly();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String execute(String command, String projectPath) {
        try {
            String guarded = "ulimit -c 0 -f 1048576 -t 30 -v 2097152 -n 256 -u 32; " + command;

            List<String> args = new ArrayList<>();
            args.add("bwrap");

            for (String dir : RO_DIRS) {
                File f = new File(dir);
                if (f.exists()) {
                    args.add("--ro-bind");
                    args.add(dir);
                    args.add(dir);
                }
            }

            // 项目根只读（防止 bash 修改源码或配置）
            args.add("--ro-bind");
            args.add(projectPath);
            args.add(projectPath);
            // output/ 和 data/ 可读写（LLM 产物写入区域）
            args.add("--bind");
            args.add(projectPath + "/output");
            args.add(projectPath + "/output");
            args.add("--bind");
            args.add(projectPath + "/data");
            args.add(projectPath + "/data");

            args.add("--bind");
            args.add("/tmp");
            args.add("/tmp");

            args.add("--dev");
            args.add("/dev");
            args.add("--proc");
            args.add("/proc");

            args.add("--unshare-net");
            args.add("--unshare-pid");
            args.add("--die-with-parent");
            args.add("--new-session");

            args.add("--chdir");
            args.add(projectPath);

            args.add("/bin/bash");
            args.add("-c");
            args.add(guarded);

            ProcessBuilder pb = new ProcessBuilder(args);
            return ProcessRunner.execute(pb, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        } catch (Exception e) {
            log.warn("bwrap 异常: {}", e.getMessage());
            return "错误：沙箱执行失败 - " + e.getMessage();
        }
    }
}
