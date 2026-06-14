package com.icusu.sivan.application.conversation.message;

import com.icusu.sivan.application.conversation.message.MessageAttachmentsSerializer;
import com.icusu.sivan.domain.conversation.Message;

import java.util.List;
import java.util.UUID;

/**
 * 图片附件富化器：为指定用户消息附加图片引用。
 * 同时从消息数据库字段读取历史图片，确保所有用户消息的图片均传入 LLM。
 */
public class ImageAttachmentEnricher implements MessageEnricher {

    private final List<String> images;
    private final UUID targetMessageId;

    /**
     * @param images          图片引用列表（为空时不执行富化）
     * @param targetMessageId 需要附加图片的消息 ID（为 null 时不限制）
     */
    public ImageAttachmentEnricher(List<String> images, UUID targetMessageId) {
        this.images = images;
        this.targetMessageId = targetMessageId;
    }

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        Message message = enriched.getOriginal();
        if (!message.isUser()) return;

        // 策略1: 从消息的 images 数据库字段读取（覆盖历史消息 + 当前消息）
        List<String> refs = null;
        if (message.getImages() != null && !message.getImages().isBlank()) {
            refs = MessageAttachmentsSerializer.deserializeImages(message.getImages());
        }

        // 策略2: 当前请求参数注入（仅在目标消息且 DB 无数据时兜底）
        if ((refs == null || refs.isEmpty()) && images != null && !images.isEmpty()) {
            if (targetMessageId == null || targetMessageId.equals(message.getMessageId())) {
                refs = images;
            }
        }

        if (refs != null && !refs.isEmpty()) {
            enriched.setImageRefs(refs);
        }
    }
}
