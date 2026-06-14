package com.icusu.sivan.infra.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱安全配置 — 生成 macOS {@code sandbox-exec} 所需的 SBPL 配置。
 * <p>
 * 默认拒绝所有操作，仅白名单通过。
 *
 * <p>权限模型：
 * <ul>
 *   <li><b>file-read*</b> — 项目目录 + 临时目录可读写，系统目录只读</li>
 *   <li><b>network*</b> — 完全阻断</li>
 *   <li><b>process-exec</b> — 仅系统工具目录</li>
 *   <li><b>process-fork</b> — 允许（shell/Python 需要）</li>
 * </ul>
 */
public class SandboxProfile {

    private final String projectPath;
    private final List<String> writablePaths = new ArrayList<>();
    private final List<String> readonlyPaths = new ArrayList<>();
    private final List<String> execPaths = new ArrayList<>();

    private SandboxProfile(String projectPath) {
        this.projectPath = projectPath;
        writablePaths.add("/private/tmp");
        writablePaths.add("/private/var/folders");
        readonlyPaths.add("/usr");
        readonlyPaths.add("/System/Library");
        readonlyPaths.add("/Library");
        readonlyPaths.add("/opt");
        readonlyPaths.add("/private/etc");
        readonlyPaths.add("/private/var");
        readonlyPaths.add("/bin");
        readonlyPaths.add("/sbin");
        execPaths.add("/bin");
        execPaths.add("/sbin");
        execPaths.add("/usr/bin");
        execPaths.add("/usr/sbin");
        execPaths.add("/usr/libexec");
        execPaths.add("/Library/Developer/CommandLineTools");
        execPaths.add("/opt/homebrew/bin");
        execPaths.add("/opt/homebrew/Cellar");
    }

    public static SandboxProfile forProject(String projectPath) {
        return new SandboxProfile(projectPath);
    }

    public SandboxProfile addWritablePath(String path) {
        writablePaths.add(path);
        return this;
    }

    public SandboxProfile addReadonlyPath(String path) {
        readonlyPaths.add(path);
        return this;
    }

    public SandboxProfile addExecPath(String path) {
        execPaths.add(path);
        return this;
    }

    public String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(deny default)\n");
        sb.append("(import \"/System/Library/Sandbox/Profiles/bsd.sb\")\n\n");

        sb.append(";; 进程：允许 fork 和执行系统工具\n");
        sb.append("(allow process-fork)\n");
        for (String p : execPaths) {
            sb.append("(allow process-exec (subpath \"").append(p).append("\"))\n");
        }
        sb.append("\n");

        sb.append(";; 文件：项目目录只读（防止 bash 修改源码或配置）\n");
        sb.append("(allow file-read* (subpath \"").append(projectPath).append("\"))\n");
        sb.append(";; 文件：output/ 和 data/ 子目录可读写（LLM 产物写入区域）\n");
        sb.append("(allow file-read* file-write* (subpath \"").append(projectPath).append("/output\"))\n");
        sb.append("(allow file-read* file-write* (subpath \"").append(projectPath).append("/data\"))\n");
        for (String p : writablePaths) {
            sb.append("(allow file-read* file-write* (subpath \"").append(p).append("\"))\n");
        }
        sb.append("\n");

        sb.append(";; 文件：系统目录只读\n");
        for (String p : readonlyPaths) {
            sb.append("(allow file-read* (subpath \"").append(p).append("\"))\n");
        }
        sb.append("\n");

        sb.append(";; 父目录元数据（PATH 解析需要）\n");
        sb.append("(allow file-read-metadata (subpath \"/\"))\n\n");

        sb.append(";; 进程间通信\n");
        sb.append("(allow ipc-posix-sem)\n");
        sb.append("(allow ipc-posix-shm)\n");
        sb.append("(allow signal (target same-sandbox))\n\n");

        // 网络：默认(deny default)已阻断所有网络，无需显式规则
        // 注意: macOS 15+ 不支持 (remote local) 过滤器

        return sb.toString();
    }
}
