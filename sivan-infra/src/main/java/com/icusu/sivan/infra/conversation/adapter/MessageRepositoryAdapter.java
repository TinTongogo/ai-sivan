package com.icusu.sivan.infra.conversation.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储适配器 — 基于 forest_nodes 表（type='message'）存储消息。
 * <p>
 * messages 表已废除，所有消息存储在 forest_nodes(type='message') 中。
 * 核心字段映射到列（sortOrder、role、content、importance、estimateTokens），
 * 其余字段存储在 metadata JSONB 中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRepositoryAdapter implements IMessageRepository {

    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    @Override
    public Optional<Message> findById(UUID messageId) {
        return forestNodeJpaRepository.findById(messageId.toString()).map(this::toDomain);
    }

    @Override
    public List<Message> findByIds(Collection<UUID> messageIds) {
        List<String> ids = messageIds.stream().map(UUID::toString).toList();
        return forestNodeJpaRepository.findAllById(ids).stream()
                .map(this::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Message> findByConversationId(UUID conversationId) {
        return forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(conversationId, "message")
                .stream()
                .map(this::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Message save(Message message) {
        String nodeId = message.getMessageId() != null ? message.getMessageId().toString() : UUID.randomUUID().toString();
        ForestNodeEntity entity = toEntity(message, nodeId);

        // 新消息自动分配 sortOrder
        if (message.getMessageId() == null && entity.getSortOrder() == null) {
            Integer maxSort = forestNodeJpaRepository.findMaxMessageSortOrder(entity.getForestId()).orElse(0);
            entity.setSortOrder(maxSort + 1);
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }

        entity.setUpdatedAt(OffsetDateTime.now());
        forestNodeJpaRepository.save(entity);

        if (message.getMessageId() == null) {
            message.setMessageId(UUID.fromString(nodeId));
        }
        message.setSortOrder(entity.getSortOrder());
        // 从数据库读取 createdAt 时间戳
        forestNodeJpaRepository.findById(nodeId).ifPresent(saved -> {
            if (saved.getUpdatedAt() != null) {
                message.setCreatedAt(saved.getUpdatedAt().toLocalDateTime());
            }
        });
        return message;
    }

    @Override
    public void delete(UUID messageId) {
        forestNodeJpaRepository.deleteById(messageId.toString());
    }

    @Override
    public void deleteByConversationId(UUID conversationId) {
        forestNodeJpaRepository.deleteByForestId(conversationId);
    }

    @Override
    public List<Message> findLatestByConversationId(UUID conversationId, int limit) {
        return forestNodeJpaRepository.findLatestMessages(conversationId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Message> findBeforeSortOrder(UUID conversationId, int beforeSortOrder, int limit) {
        return forestNodeJpaRepository.findMessagesBeforeSortOrder(conversationId, beforeSortOrder, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public int countByConversationId(UUID conversationId) {
        return forestNodeJpaRepository.countMessagesByForestId(conversationId);
    }

    @Override
    public int countBeforeSortOrder(UUID conversationId, int beforeSortOrder) {
        return forestNodeJpaRepository.countMessagesBeforeSortOrder(conversationId, beforeSortOrder);
    }

    @Override
    public Optional<Message> findLatestUserMessage(UUID conversationId) {
        List<ForestNodeEntity> results = forestNodeJpaRepository.findLatestUserMessage(conversationId, PageRequest.of(0, 1));
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(toDomain(results.getFirst()));
    }

    @Override
    public List<Message> findByGenerationGroup(UUID generationGroup) {
        // generationGroup 存储在 metadata JSONB 中，in-memory 过滤（结果集通常 < 10）
        String groupStr = generationGroup.toString();
        return forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(null, "message")
                .stream()
                .map(this::toDomain)
                .filter(m -> {
                    if (m == null) return false;
                    try {
                        String raw = forestNodeJpaRepository.findById(m.getMessageId().toString())
                                .map(e -> extractFromMetadata(e.getMetadata(), "generationGroup"))
                                .orElse(null);
                        return groupStr.equals(raw);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted(Comparator.comparingInt(
                        m -> m.getGenerationIndex() != null ? m.getGenerationIndex() : 0))
                .toList();
    }

    @Override
    public int countByGenerationGroup(UUID generationGroup) {
        return findByGenerationGroup(generationGroup).size();
    }

    @Override
    public List<Message> search(UUID accountId, String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return forestNodeJpaRepository.searchMessages(accountId, keyword.trim(), size, page * size)
                .stream()
                .map(this::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    // ---- 转换方法 ----

    /** ForestNodeEntity → Message */
    @SuppressWarnings("unchecked")
    private Message toDomain(ForestNodeEntity entity) {
        try {
            Map<String, Object> meta = parseMetadata(entity.getMetadata());

            MessageStatus status = null;
            if (meta.containsKey("status")) {
                try {
                    status = MessageStatus.valueOf((String) meta.get("status"));
                } catch (Exception ignored) {}
            }

            return Message.builder()
                    .messageId(UUID.fromString(entity.getNodeId()))
                    .conversationId(entity.getForestId())
                    .accountId(meta.containsKey("accountId")
                            ? UUID.fromString((String) meta.get("accountId")) : null)
                    .projectId(meta.containsKey("projectId")
                            ? UUID.fromString((String) meta.get("projectId")) : null)
                    .role(entity.getRole())
                    .content(entity.getContent())
                    .contentType((String) meta.getOrDefault("contentType", "text"))
                    .msgType((String) meta.getOrDefault("msgType", "normal"))
                    .importance(entity.getImportance() != null ? entity.getImportance() : 0.0)
                    .thinking((String) meta.get("thinking"))
                    .targetAgent((String) meta.get("targetAgent"))
                    .replyToId(meta.containsKey("replyToId")
                            ? UUID.fromString((String) meta.get("replyToId")) : null)
                    .sortOrder(entity.getSortOrder())
                    .status(status)
                    .rating((String) meta.get("rating"))
                    .model((String) meta.get("model"))
                    .totalTokens(entity.getEstimateTokens() != null
                            ? entity.getEstimateTokens().intValue() : null)
                    .durationMs(meta.containsKey("durationMs")
                            ? ((Number) meta.get("durationMs")).intValue() : null)
                    .thinkingDurationMs(meta.containsKey("thinkingDurationMs")
                            ? ((Number) meta.get("thinkingDurationMs")).intValue() : null)
                    .thinkingTokens(meta.containsKey("thinkingTokens")
                            ? ((Number) meta.get("thinkingTokens")).intValue() : null)
                    .images((String) meta.get("images"))
                    .audios((String) meta.get("audios"))
                    .attachments((String) meta.get("attachments"))
                    .generationIndex(meta.containsKey("generationIndex")
                            ? ((Number) meta.get("generationIndex")).intValue() : 1)
                    .generationGroup(meta.containsKey("generationGroup")
                            ? UUID.fromString((String) meta.get("generationGroup")) : null)
                    .sections((String) meta.get("sections"))
                    .progress((String) meta.get("progress"))
                    .createdAt(entity.getUpdatedAt() != null
                            ? entity.getUpdatedAt().toLocalDateTime() : null)
                    .build();
        } catch (Exception e) {
            log.warn("转换消息节点失败: nodeId={}", entity.getNodeId());
            return null;
        }
    }

    /** Message → ForestNodeEntity */
    private ForestNodeEntity toEntity(Message message, String nodeId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (message.getAccountId() != null) meta.put("accountId", message.getAccountId().toString());
        if (message.getProjectId() != null) meta.put("projectId", message.getProjectId().toString());
        if (message.getContentType() != null) meta.put("contentType", message.getContentType());
        if (message.getMsgType() != null) meta.put("msgType", message.getMsgType());
        if (message.getThinking() != null) meta.put("thinking", message.getThinking());
        if (message.getTargetAgent() != null) meta.put("targetAgent", message.getTargetAgent());
        if (message.getReplyToId() != null) meta.put("replyToId", message.getReplyToId().toString());
        if (message.getStatus() != null) meta.put("status", message.getStatus().name());
        if (message.getRating() != null) meta.put("rating", message.getRating());
        if (message.getModel() != null) meta.put("model", message.getModel());
        if (message.getDurationMs() != null) meta.put("durationMs", message.getDurationMs());
        if (message.getThinkingDurationMs() != null) meta.put("thinkingDurationMs", message.getThinkingDurationMs());
        if (message.getThinkingTokens() != null) meta.put("thinkingTokens", message.getThinkingTokens());
        if (message.getImages() != null) meta.put("images", message.getImages());
        if (message.getAudios() != null) meta.put("audios", message.getAudios());
        if (message.getAttachments() != null) meta.put("attachments", message.getAttachments());
        if (message.getGenerationIndex() != null) meta.put("generationIndex", message.getGenerationIndex());
        if (message.getGenerationGroup() != null) meta.put("generationGroup", message.getGenerationGroup().toString());
        if (message.getSections() != null) meta.put("sections", message.getSections());
        if (message.getProgress() != null) meta.put("progress", message.getProgress());

        return ForestNodeEntity.builder()
                .nodeId(nodeId)
                .forestId(message.getConversationId())
                .nodeType("message")
                .sortOrder(message.getSortOrder())
                .role(message.getRole())
                .content(message.getContent() != null ? message.getContent() : "")
                .importance(message.getImportance() != null ? message.getImportance() : 0.0)
                .estimateTokens(message.getTotalTokens() != null
                        ? message.getTotalTokens().longValue() : null)
                .metadata(toJson(meta))
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // ---- JSON 工具方法 ----

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析 metadata JSON 失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("序列化 metadata 失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** 从 metadata JSON 中提取指定字段。 */
    private String extractFromMetadata(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> meta = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object val = meta.get(key);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
