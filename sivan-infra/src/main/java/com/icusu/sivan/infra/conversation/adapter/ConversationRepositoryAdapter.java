package com.icusu.sivan.infra.conversation.adapter;

import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话仓储适配器 — 完全基于 forest_nodes 表存储对话。
 * forests 表已废弃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationRepositoryAdapter implements IConversationRepository {

    private final ForestNodeJpaRepository forestNodeJpaRepository;

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return forestNodeJpaRepository.findById(conversationId.toString())
                .filter(e -> "conversation".equals(e.getNodeType()))
                .map(this::toDomain);
    }

    @Override
    public List<Conversation> findAllByAccount(UUID accountId) {
        return forestNodeJpaRepository.findConversationsByAccount(accountId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Conversation> findAllByAccountAndProject(UUID accountId, UUID projectId) {
        return forestNodeJpaRepository.findConversationsByAccountAndProject(accountId, projectId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void save(Conversation conversation) {
        UUID id = conversation.getConversationId() != null
                ? conversation.getConversationId() : UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        var existingNode = forestNodeJpaRepository.findById(id.toString());
        if (existingNode.isPresent()) {
            ForestNodeEntity e = existingNode.get();
            e.setContent(conversation.getTitle() != null ? conversation.getTitle() : "新对话");
            if (conversation.getProjectId() != null) e.setProjectId(conversation.getProjectId());
            e.setUpdatedAt(now);
            forestNodeJpaRepository.save(e);
            conversation.setCreatedAt(e.getCreatedAt() != null ? e.getCreatedAt().toLocalDateTime() : null);
        } else {
            ForestNodeEntity convNode = ForestNodeEntity.builder()
                    .nodeId(id.toString())
                    .forestId(id)
                    .nodeType("conversation")
                    .sortOrder(0)
                    .content(conversation.getTitle() != null ? conversation.getTitle() : "新对话")
                    .accountId(conversation.getAccountId())
                    .projectId(conversation.getProjectId())
                    .kind("INSTANCE")
                    .updatedAt(now)
                    .createdAt(now)
                    .build();
            forestNodeJpaRepository.save(convNode);
            conversation.setCreatedAt(now.toLocalDateTime());
        }

        conversation.setConversationId(id);
        conversation.setUpdatedAt(now.toLocalDateTime());
    }

    @Override
    public void update(Conversation conversation) {
        forestNodeJpaRepository.findById(conversation.getConversationId().toString()).ifPresent(entity -> {
            entity.setContent(conversation.getTitle());
            if (conversation.getProjectId() != null) entity.setProjectId(conversation.getProjectId());
            entity.setUpdatedAt(OffsetDateTime.now());
            forestNodeJpaRepository.save(entity);
        });
    }

    @Override
    public void delete(UUID conversationId) {
        forestNodeJpaRepository.deleteByForestId(conversationId);
    }

    @Override
    public long countByAccount(UUID accountId) {
        return forestNodeJpaRepository.countConversationsByAccount(accountId);
    }

    private Conversation toDomain(ForestNodeEntity entity) {
        UUID forestId = entity.getForestId();
        int msgCount = forestNodeJpaRepository.countMessagesByForestId(forestId);
        OffsetDateTime lastMsgAt = null;
        var latest = forestNodeJpaRepository.findLatestMessages(forestId, PageRequest.of(0, 1));
        if (!latest.isEmpty() && latest.getFirst().getUpdatedAt() != null) {
            lastMsgAt = latest.getFirst().getUpdatedAt();
        }

        return Conversation.builder()
                .conversationId(forestId)
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .title(entity.getContent() != null ? entity.getContent() : "")
                .messageCount(msgCount)
                .knowledgeBaseIds(Collections.emptyList())
                .mcpServerIds(Collections.emptyList())
                .lastMessageAt(lastMsgAt != null ? lastMsgAt.toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null
                        ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null
                        ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }
}
