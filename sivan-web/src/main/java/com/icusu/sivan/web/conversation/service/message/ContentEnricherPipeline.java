package com.icusu.sivan.web.conversation.service.message;

import com.icusu.sivan.domain.conversation.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容富化管道链，顺序执行一组 MessageEnricher。
 * 每个 enricher 负责向 EnrichedMessage 注入一种维度的附加内容。
 */
public class ContentEnricherPipeline {

    private final List<MessageEnricher> enrichers = new ArrayList<>();

    /** 添加一个富化器到管道链。 */
    public ContentEnricherPipeline addEnricher(MessageEnricher enricher) {
        enrichers.add(enricher);
        return this;
    }

    /**
     * 对截断后的消息列表执行全部富化步骤。
     *
     * @param truncatedMessages 已截断的消息（将被富化）
     * @param allMessages       完整消息列表（供 enricher 上下文查询）
     * @return 富化后的消息列表
     */
    public List<EnrichedMessage> enrich(List<Message> truncatedMessages, List<Message> allMessages) {
        List<EnrichedMessage> results = new ArrayList<>(truncatedMessages.size());
        for (Message msg : truncatedMessages) {
            results.add(new EnrichedMessage(msg));
        }

        for (MessageEnricher enricher : enrichers) {
            for (EnrichedMessage em : results) {
                enricher.enrich(em, allMessages);
            }
        }

        return results;
    }
}
