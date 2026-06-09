package com.icusu.sivan.domain.forest.service;

/**
 * Agent 间消息 — 在 {@link AgentMessageBus} 上传输。
 * <p>
 * 每个 {@code InnerGoal}（里程碑）范围内的 Agent 通过此消息通信。
 *
 * @param sourceAgentId 发送方 Agent ID
 * @param targetAgentId 接收方 Agent ID（null 表示广播）
 * @param topic         消息主题 / correlationId
 * @param content       消息内容
 * @param type          消息类型
 */
public record AgentMessage(
        String sourceAgentId,
        String targetAgentId,
        String topic,
        String content,
        MessageType type
) {

    public enum MessageType {
        REQUEST,
        RESPONSE,
        BROADCAST,
        DELEGATE
    }
}
