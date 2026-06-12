package com.icusu.sivan.domain.tool;

/**
 * MCP 工具预检结果（07-工具动态感知 §4.5）。
 *
 * @param serverId        MCP 服务器 ID
 * @param toolName        工具名称（"*" 表示服务器级别）
 * @param available       是否可用
 * @param message         提示信息（null 表示正常）
 * @param credentialValid 凭证是否有效
 */
public record PreflightResult(
        String serverId,
        String toolName,
        boolean available,
        String message,
        boolean credentialValid
) {
    public PreflightResult(String serverId, String toolName, boolean available, String message) {
        this(serverId, toolName, available, message, available);
    }

    /** 是否为服务器级别检查。 */
    public boolean isServerLevel() {
        return "*".equals(toolName);
    }

    /** 是否可安全执行。 */
    public boolean isReady() {
        return available && credentialValid;
    }

    /** 格式化为可读文本。 */
    public String toDisplay() {
        if (!credentialValid) return "⚠️ " + serverId + " 凭证已失效，需要重新授权";
        if (available) return "✅ " + serverId + "/" + toolName + " 可用";
        if (isServerLevel()) return "❌ " + serverId + " 不可用: " + message;
        return "⚠️ " + serverId + "/" + toolName + " 不可用: " + message;
    }
}
