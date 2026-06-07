package com.icusu.sivan.web.conversation.service.message;

import com.icusu.sivan.domain.conversation.Message;

import java.util.List;
import java.util.UUID;

/**
 * 音频附件富化器：为指定用户消息附加音频引用。
 * 同时从消息数据库字段读取历史音频，确保所有用户消息的音频均传入 LLM。
 */
public class AudioAttachmentEnricher implements MessageEnricher {

    private final List<String> audios;
    private final UUID targetMessageId;

    /**
     * @param audios          音频引用列表（为空时不执行富化）
     * @param targetMessageId 需要附加音频的消息 ID（为 null 时不限制）
     */
    public AudioAttachmentEnricher(List<String> audios, UUID targetMessageId) {
        this.audios = audios;
        this.targetMessageId = targetMessageId;
    }

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        Message message = enriched.getOriginal();
        if (!message.isUser()) return;

        // 策略1: 从消息的 audios 数据库字段读取（历史消息 + 当前消息）
        List<String> refs = null;
        if (message.getAudios() != null && !message.getAudios().isBlank()) {
            refs = MessageAttachmentsSerializer.deserializeAudios(message.getAudios());
        }

        // 策略2: 当前请求参数注入（仅在目标消息且 DB 无数据时兜底）
        if ((refs == null || refs.isEmpty()) && audios != null && !audios.isEmpty()) {
            if (targetMessageId == null || targetMessageId.equals(message.getMessageId())) {
                refs = audios;
            }
        }

        if (refs != null && !refs.isEmpty()) {
            enriched.setAudioRefs(refs);
        }
    }
}
