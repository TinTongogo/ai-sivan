package com.icusu.sivan.application.conversation.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 消息附件序列化/反序列化工具。统一处理 images / audios / attachments 三种多模态附件。
 */
@Slf4j
public final class MessageAttachmentsSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> ATTACHMENTS_TYPE = new TypeReference<>() {};

    private MessageAttachmentsSerializer() {}

    // ===== 序列化 =====

    public static String serializeImages(List<String> images) {
        return serializeToString(images);
    }

    public static String serializeAudios(List<String> audios) {
        return serializeToString(audios);
    }

    public static String serializeAttachments(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            log.warn("序列化附件列表失败", e);
            return null;
        }
    }

    // ===== 反序列化 =====

    public static List<String> deserializeImages(String imagesJson) {
        return deserializeStringList(imagesJson);
    }

    public static List<String> deserializeAudios(String audiosJson) {
        return deserializeStringList(audiosJson);
    }

    public static List<Map<String, Object>> deserializeAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) return null;
        try {
            return MAPPER.readValue(attachmentsJson, ATTACHMENTS_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("反序列化附件列表失败", e);
            return null;
        }
    }

    // ===== 内部实现 =====

    private static String serializeToString(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("序列化列表失败", e);
            return null;
        }
    }

    private static List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("反序列化列表失败", e);
            return null;
        }
    }
}
