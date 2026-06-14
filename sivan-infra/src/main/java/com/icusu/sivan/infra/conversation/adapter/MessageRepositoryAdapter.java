package com.icusu.sivan.infra.conversation.adapter;

import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.infra.conversation.entity.MessageEntity;
import com.icusu.sivan.infra.conversation.repository.MessageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储适配器，实现 IMessageRepository。
 */
@Component
@RequiredArgsConstructor
public class MessageRepositoryAdapter implements IMessageRepository {

    private final MessageJpaRepository jpaRepository;

    /** 根据 ID 查询消息。 */
    @Override
    public Optional<Message> findById(UUID messageId) {
        return jpaRepository.findById(messageId).map(this::toDomain);
    }

    /** 批量根据 ID 查询消息。 */
    @Override
    public List<Message> findByIds(Collection<UUID> messageIds) {
        return jpaRepository.findAllById(messageIds).stream()
                .map(this::toDomain).toList();
    }

    /** 根据对话 ID 查询消息列表。 */
    @Override
    public List<Message> findByConversationId(UUID conversationId) {
        return jpaRepository.findByConversationIdOrderBySortOrderAsc(conversationId)
                .stream().map(this::toDomain).toList();
    }

    /** 保存消息。 */
    @Override
    public Message save(Message message) {
        MessageEntity entity = toEntity(message);
        // 新消息：自动分配 sortOrder 避免并发冲突
        if (message.getMessageId() == null) {
            Integer maxSort = jpaRepository.findMaxSortOrderByConversationId(entity.getConversationId()).orElse(0);
            entity.setSortOrder(maxSort + 1);
        }
        jpaRepository.save(entity);
        if (message.getMessageId() == null) {
            message.setMessageId(entity.getMessageId());
        }
        message.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        // 回写仓储自动生成的 sortOrder
        message.setSortOrder(entity.getSortOrder());
        return message;
    }

    /** 删除消息。 */
    @Override
    public void delete(UUID messageId) {
        jpaRepository.deleteById(messageId);
    }

    /** 删除指定对话的所有消息。 */
    @Override
    public void deleteByConversationId(UUID conversationId) {
        jpaRepository.deleteByConversationId(conversationId);
    }

    /** 分页查询：获取最新的消息（按 sortOrder 倒序）。 */
    @Override
    public List<Message> findLatestByConversationId(UUID conversationId, int limit) {
        return jpaRepository.findByConversationIdOrderBySortOrderDesc(
                        conversationId, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    /** 分页查询：获取比 beforeSortOrder 更旧的消息。 */
    @Override
    public List<Message> findBeforeSortOrder(UUID conversationId, int beforeSortOrder, int limit) {
        return jpaRepository.findBeforeSortOrder(conversationId, beforeSortOrder, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    /** 查询会话消息总数 */
    @Override
    public int countByConversationId(UUID conversationId) {
        return jpaRepository.countByConversationId(conversationId);
    }

    /** 统计比指定 sortOrder 更旧的消息数 */
    @Override
    public int countBeforeSortOrder(UUID conversationId, int beforeSortOrder) {
        return jpaRepository.countBeforeSortOrder(conversationId, beforeSortOrder);
    }

    /** 获取会话中最新的一条用户消息 */
    @Override
    public Optional<Message> findLatestUserMessage(UUID conversationId) {
        return jpaRepository.findFirstByConversationIdAndRoleOrderBySortOrderDesc(conversationId, "user")
                .map(this::toDomain);
    }

    /** 根据生成组 ID 查找消息列表 */
    @Override
    public List<Message> findByGenerationGroup(UUID generationGroup) {
        return jpaRepository.findByGenerationGroupOrderByGenerationIndexAsc(generationGroup)
                .stream().map(this::toDomain).toList();
    }

    /** 统计指定生成组中的消息数量 */
    @Override
    public int countByGenerationGroup(UUID generationGroup) {
        return jpaRepository.countByGenerationGroup(generationGroup);
    }

    @Override
    public List<Message> search(UUID accountId, String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return jpaRepository.searchByContent(accountId, keyword.trim(), size, page * size)
                .stream()
                .map(this::toDomain)
                .toList();
    }
    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private Message toDomain(MessageEntity entity) {
        return Message.builder()
                .messageId(entity.getMessageId())
                .conversationId(entity.getConversationId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .role(entity.getRole())
                .content(entity.getContent())
                .thinking(entity.getThinking())
                .contentType(entity.getContentType())
                .targetAgent(entity.getTargetAgent())
                .replyToId(entity.getReplyToId())
                .sortOrder(entity.getSortOrder())
                .status(entity.getStatus() != null ? MessageStatus.valueOf(entity.getStatus()) : null)
                .rating(entity.getRating())
                .model(entity.getModel())
                .totalTokens(entity.getTotalTokens())
                .durationMs(entity.getDurationMs())
                .thinkingDurationMs(entity.getThinkingDurationMs())
                .thinkingTokens(entity.getThinkingTokens())
                .chain(entity.getChain())
                .images(entity.getImages())
                .attachments(entity.getAttachments())
                .generationIndex(entity.getGenerationIndex())
                .generationGroup(entity.getGenerationGroup())
                .sections(entity.getSections())
                .audios(entity.getAudios())
                .msgType(entity.getMsgType())
                .importance(entity.getImportance())
                .progress(entity.getProgress())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private MessageEntity toEntity(Message message) {
        MessageEntity entity = new MessageEntity();
        entity.setMessageId(message.getMessageId());
        entity.setConversationId(message.getConversationId());
        entity.setAccountId(message.getAccountId());
        entity.setProjectId(message.getProjectId());
        entity.setRole(message.getRole());
        entity.setContent(message.getContent());
        entity.setThinking(message.getThinking());
        entity.setContentType(message.getContentType() != null ? message.getContentType() : "text");
        entity.setTargetAgent(message.getTargetAgent());
        entity.setReplyToId(message.getReplyToId());
        entity.setSortOrder(message.getSortOrder());
        entity.setStatus(message.getStatus() != null ? message.getStatus().name() : "COMPLETED");
        entity.setRating(message.getRating());
        entity.setModel(message.getModel());
        entity.setTotalTokens(message.getTotalTokens());
        entity.setDurationMs(message.getDurationMs());
        entity.setThinkingDurationMs(message.getThinkingDurationMs());
        entity.setThinkingTokens(message.getThinkingTokens());
        entity.setChain(message.getChain());
        entity.setImages(message.getImages());
        entity.setAttachments(message.getAttachments());
        entity.setGenerationIndex(message.getGenerationIndex() != null ? message.getGenerationIndex() : 1);
        entity.setGenerationGroup(message.getGenerationGroup());
        entity.setSections(message.getSections());
        entity.setAudios(message.getAudios());
        entity.setMsgType(message.getMsgType());
        entity.setImportance(message.getImportance());
        entity.setProgress(message.getProgress());
        return entity;
    }
}
