package com.icusu.sivan.application.conversation;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.util.OwnershipValidator;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.application.service.GroupService;
import com.icusu.sivan.application.conversation.dto.ConversationResponse;
import com.icusu.sivan.application.conversation.dto.CreateConversationRequest;
import com.icusu.sivan.application.conversation.dto.UpdateConversationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 对话 CRUD 服务 — 对话的增删改查与所有权校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationCrudService {

    private final IConversationRepository conversationRepository;
    private final GroupService groupService;
    private final IMessageRepository messageRepository;

    /**
     * 创建新对话。
     */
    public ConversationResponse create(UUID accountId, CreateConversationRequest request) {
        UUID projectId = request.getProjectId();
        if (projectId == null) {
            throw new DomainException("请先选择或创建一个项目");
        }
        var project = groupService.findOwned(accountId, projectId);
        if (Boolean.TRUE.equals(project.getArchived())) {
            throw new DomainException("项目已归档，无法创建新对话");
        }
        Conversation conversation = Conversation.builder()
                .accountId(accountId)
                .projectId(projectId)
                .title(request.getTitle() != null ? request.getTitle() : "新对话")
                .messageCount(0)
                .build();

        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    /**
     * 根据 ID 查询对话。
     */
    public ConversationResponse getById(UUID accountId, UUID conversationId) {
        return toResponse(findOwned(accountId, conversationId));
    }

    /**
     * 查询对话列表。
     */
    public List<ConversationResponse> list(UUID accountId, UUID projectId) {
        List<Conversation> conversations = projectId != null
                ? conversationRepository.findAllByAccountAndProject(accountId, projectId)
                : conversationRepository.findAllByAccount(accountId);
        return conversations.stream().map(this::toResponse).toList();
    }

    /**
     * 更新对话（标题、项目、知识库绑定）。
     */
    public ConversationResponse update(UUID accountId, UUID conversationId, UpdateConversationRequest request) {
        Conversation conversation = findOwned(accountId, conversationId);
        conversation.updateFrom(request.getTitle(), request.getProjectId(),
                request.getKnowledgeBaseIds(), request.getMcpServerIds());
        conversationRepository.update(conversation);
        return toResponse(conversation);
    }

    /**
     * 删除对话及关联消息。
     */
    @Transactional
    public void delete(UUID accountId, UUID conversationId) {
        Conversation conversation = findOwned(accountId, conversationId);
        messageRepository.deleteByConversationId(conversation.getConversationId());
        conversationRepository.delete(conversation.getConversationId());
    }

    /**
     * 查找当前用户拥有的对话。
     */
    public Conversation findOwned(UUID accountId, UUID conversationId) {
        return OwnershipValidator.findOwned(accountId, "对话", conversationId,
                conversationRepository::findById, Conversation::getAccountId);
    }

    /**
     * 对话实体转为响应对象。
     */
    public ConversationResponse toResponse(Conversation conversation) {
        return ConversationResponse.builder()
                .conversationId(conversation.getConversationId())
                .projectId(conversation.getProjectId())
                .title(conversation.getTitle())
                .messageCount(conversation.getMessageCount())
                .knowledgeBaseIds(conversation.getKnowledgeBaseIds())
                .mcpServerIds(conversation.getMcpServerIds())
                .lastMessageAt(conversation.getLastMessageAt())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }
}
