package com.icusu.sivan.application.conversation.message;

import com.icusu.sivan.domain.conversation.Message;

import java.util.List;

/**
 * 消息富化器接口。每个实现负责向消息注入一种维度的附加内容。
 */
@FunctionalInterface
public interface MessageEnricher {

    /**
     * 对单条消息进行富化。
     *
     * @param enriched     待富化的消息
     * @param allMessages  完整消息列表（用于上下文查询）
     */
    void enrich(EnrichedMessage enriched, List<Message> allMessages);
}
