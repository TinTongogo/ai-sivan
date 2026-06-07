package com.icusu.sivan.infra.conversation.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.infra.conversation.entity.ConversationEntity;
import com.icusu.sivan.infra.conversation.repository.ConversationJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话仓储适配器，实现 IConversationRepository。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationRepositoryAdapter implements IConversationRepository {

    private final ConversationJpaRepository jpaRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 根据 ID 查询对话。 */
    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return jpaRepository.findById(conversationId).map(this::toDomain);
    }

    /** 查询账号下所有对话。 */
    @Override
    public List<Conversation> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 查询账号下指定项目的对话。 */
    @Override
    public List<Conversation> findAllByAccountAndProject(UUID accountId, UUID projectId) {
        return jpaRepository.findByAccountIdAndProjectIdOrderByCreatedAtDesc(accountId, projectId).stream()
                .map(this::toDomain).toList();
    }

    /** 保存对话，回写 ID 和时间戳。 */
    @Override
    public void save(Conversation conversation) {
        ConversationEntity entity = toEntity(conversation);
        jpaRepository.save(entity);
        if (conversation.getConversationId() == null) {
            conversation.setConversationId(entity.getConversationId());
        }
        conversation.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        conversation.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 更新对话信息。 */
    @Override
    public void update(Conversation conversation) {
        ConversationEntity entity = jpaRepository.findById(conversation.getConversationId()).orElse(null);
        if (entity == null) return;
        entity.setTitle(conversation.getTitle());
        entity.setProjectId(conversation.getProjectId());
        entity.setKnowledgeBaseIds(toJsonArray(conversation.getKnowledgeBaseIds()));
        entity.setMcpServerIds(toJsonArray(conversation.getMcpServerIds()));
        entity.setGoalId(conversation.getGoalId());
        entity.setCompressedContext(conversation.getCompressedContext());
        entity.setCompressedUpToMsgId(conversation.getCompressedUpToMsgId());
        entity.setMessageCount(conversation.getMessageCount() != null ? conversation.getMessageCount() : entity.getMessageCount());
        if (conversation.getLastMessageAt() != null) {
            entity.setLastMessageAt(conversation.getLastMessageAt().atOffset(ZoneOffset.UTC));
        }
        jpaRepository.save(entity);
        conversation.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /** 根据 ID 删除对话。 */
    @Override
    public void delete(UUID conversationId) {
        jpaRepository.deleteById(conversationId);
    }

    /** 统计账号下对话总数。 */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private Conversation toDomain(ConversationEntity entity) {
        return Conversation.builder()
                .conversationId(entity.getConversationId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .title(entity.getTitle())
                .messageCount(entity.getMessageCount())
                .knowledgeBaseIds(toList(entity.getKnowledgeBaseIds()))
                .mcpServerIds(toList(entity.getMcpServerIds()))
                .goalId(entity.getGoalId())
                .compressedContext(entity.getCompressedContext())
                .compressedUpToMsgId(entity.getCompressedUpToMsgId())
                .lastMessageAt(entity.getLastMessageAt() != null ? entity.getLastMessageAt().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private ConversationEntity toEntity(Conversation conversation) {
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationId(conversation.getConversationId());
        entity.setAccountId(conversation.getAccountId());
        entity.setProjectId(conversation.getProjectId());
        entity.setTitle(conversation.getTitle() != null ? conversation.getTitle() : "新对话");
        entity.setKnowledgeBaseIds(toJsonArray(conversation.getKnowledgeBaseIds()));
        entity.setMcpServerIds(toJsonArray(conversation.getMcpServerIds()));
        entity.setGoalId(conversation.getGoalId());
        entity.setCompressedContext(conversation.getCompressedContext());
        entity.setCompressedUpToMsgId(conversation.getCompressedUpToMsgId());
        entity.setMessageCount(conversation.getMessageCount() != null ? conversation.getMessageCount() : 0);
        if (conversation.getLastMessageAt() != null) {
            entity.setLastMessageAt(conversation.getLastMessageAt().atOffset(ZoneOffset.UTC));
        }
        return entity;
    }

    /** 将列表序列化为 JSON 数组字符串。 */
    private String toJsonArray(List<String> list) {
        if (list == null) return "[]";
        try { return MAPPER.writeValueAsString(list); }
        catch (Exception e) { log.warn("序列化列表失败, 返回空数组", e); return "[]"; }
    }

    /** 将 JSON 数组字符串解析为列表。 */
    private List<String> toList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try { return MAPPER.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { log.warn("反序列化列表失败, 返回空列表", e); return Collections.emptyList(); }
    }
}
