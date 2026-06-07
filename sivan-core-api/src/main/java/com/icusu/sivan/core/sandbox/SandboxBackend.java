package com.icusu.sivan.core.sandbox;

/**
 * 沙箱执行后端 — 核心端口抽象。
 * <p>
 * 不同平台提供不同实现：
 * <ul>
 *   <li>macOS — sandbox-exec（SBPL 配置）</li>
 *   <li>Linux — bubblewrap（bwrap）</li>
 *   <li>其他 — 降级 ulimit（无 OS 级隔离）</li>
 * </ul>
 */
public interface SandboxBackend {

    /** 后端标识名（用于日志） */
    String name();

    /** 此环境是否可用 */
    boolean available();

    /**
     * 在隔离环境中执行命令。
     *
     * @param command     要执行的 shell 命令
     * @param projectPath 项目根目录
     * @return 命令输出
     */
    String execute(String command, String projectPath);
}
