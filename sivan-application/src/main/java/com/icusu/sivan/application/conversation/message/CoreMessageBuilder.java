package com.icusu.sivan.application.conversation.message;

import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.domain.file.FileStoragePort;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 将富化后的消息列表组装为核心 {@link Msg} 列表。
 */
public class CoreMessageBuilder {

    private final FileStoragePort fileStorageService;

    public CoreMessageBuilder(FileStoragePort fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 构建 LLM 消息列表。
     *
     * @param systemPrompt      system prompt
     * @param enriched          富化后的消息列表
     * @param excludeMessageId  需要排除的消息 ID（重新生成时跳过旧的 AI 消息）
     * @return 核心 Message 列表
     */
    public List<Msg> build(String systemPrompt,
                           List<EnrichedMessage> enriched,
                           UUID excludeMessageId,
                           UUID accountId) {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, systemPrompt));

        for (EnrichedMessage em : enriched) {
            com.icusu.sivan.domain.conversation.Message original = em.getOriginal();
            if (excludeMessageId != null && excludeMessageId.equals(original.getMessageId())) continue;

            Role role = original.isUser() ? Role.USER : Role.ASSISTANT;
            String content = em.getEnrichedContent();

            if (!em.isImagesAttached() && !em.isAudiosAttached()) {
                msgs.add(Msg.of(role, content));
            } else {
                var builder = Msg.builder().role(role).text(content);
                if (em.isImagesAttached()) {
                    for (String imageRef : em.getImageRefs()) {
                        builder.add(new Content.Image(resolveMimeType(imageRef), resolveBytes(imageRef, accountId)));
                    }
                }
                if (em.isAudiosAttached()) {
                    for (String audioRef : em.getAudioRefs()) {
                        builder.add(new Content.Audio(resolveMimeType(audioRef), resolveBytes(audioRef, accountId), null));
                    }
                }
                msgs.add(builder.build());
            }
        }

        return msgs;
    }

    private byte[] resolveBytes(String ref, UUID accountId) {
        if (ref == null) throw new IllegalArgumentException("ref 不能为空");
        if (ref.startsWith("data:")) {
            int comma = ref.indexOf(',');
            String base64Data = comma > 0 ? ref.substring(comma + 1) : ref;
            return Base64.getDecoder().decode(base64Data);
        }
        try {
            UUID.fromString(ref);
            String dataUri = fileStorageService.resolveToBase64(accountId, UUID.fromString(ref));
            int comma = dataUri.indexOf(',');
            String base64Data = comma > 0 ? dataUri.substring(comma + 1) : dataUri;
            return Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的引用: " + ref, e);
        }
    }

    private static String resolveMimeType(String ref) {
        if (ref != null && ref.startsWith("data:")) {
            String rest = ref.substring("data:".length());
            int semicolon = rest.indexOf(';');
            if (semicolon > 0) return rest.substring(0, semicolon);
        }
        return "image/png";
    }

}
