package com.icusu.sivan.application.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.IntentClassifier;
import com.icusu.sivan.application.conversation.dto.MessagePageResponse;
import com.icusu.sivan.application.conversation.dto.MessageResponse;
import com.icusu.sivan.application.conversation.dto.SendMessageRequest;
import com.icusu.sivan.application.conversation.message.MessageAttachmentsSerializer;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 消息 CRUD 服务 — 消息的增删改查、分页、响应映射。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageCrudService {

    private final IMessageRepository messageRepository;
    private final IConversationRepository conversationRepository;
    private final ConversationCrudService conversationCrudService;
    private final IntentClassifier intentClassifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 发送用户消息（非流式）。
     */
    @Transactional
    public MessageResponse sendMessage(UUID accountId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = conversationCrudService.findOwned(accountId, conversationId);

        // 解析 replyToId：若引用的是旧版本，重定向到同组最新版本
        UUID resolvedReplyToId = resolveLatestGeneration(request.getReplyToId());

        Message message = Message.builder()
                .conversationId(conversationId)
                .accountId(accountId)
                .projectId(conversation.getProjectId())
                .role(Message.ROLE_USER)
                .content(request.getContent())
                .contentType(request.getContentType() != null ? request.getContentType() : "text")
                .targetAgent(request.getTargetAgent())
                .replyToId(resolvedReplyToId)
                .status(MessageStatus.COMPLETED)
                .images(MessageAttachmentsSerializer.serializeImages(request.getImages()))
                .audios(MessageAttachmentsSerializer.serializeAudios(request.getAudios()))
                .attachments(MessageAttachmentsSerializer.serializeAttachments(request.getAttachments()))
                .build();

        message = messageRepository.save(message);

        conversation.incrementMessageCount();
        conversationRepository.update(conversation);

        return toMessageResponse(message);
    }

    /**
     * 若 replyToId 指向的消息有 generationGroup，重定向到同组中 generationIndex 最大的最新版本。
     */
    public UUID resolveLatestGeneration(UUID replyToId) {
        if (replyToId == null) return null;
        return messageRepository.findById(replyToId)
                .flatMap(target -> {
                    UUID genGroup = target.getGenerationGroup();
                    if (genGroup == null) return java.util.Optional.empty();
                    return messageRepository.findByGenerationGroup(genGroup).stream()
                            .max(java.util.Comparator.comparingInt(
                                    m -> m.getGenerationIndex() != null ? m.getGenerationIndex() : 0))
                            .map(Message::getMessageId)
                            .filter(latestId -> !latestId.equals(replyToId));
                })
                .orElse(replyToId);
    }

    /**
     * 获取消息列表（默认分页）。
     */
    public MessagePageResponse getMessages(UUID accountId, UUID conversationId) {
        return getMessages(accountId, conversationId, null, 50);
    }

    /**
     * 分页查询消息（支持游标分页），返回带 hasMore 标记的分页结果。
     */
    public MessagePageResponse getMessages(UUID accountId, UUID conversationId, Integer beforeSortOrder, int limit) {
        conversationCrudService.findOwned(accountId, conversationId);
        List<Message> msgs;
        if (beforeSortOrder != null) {
            msgs = messageRepository.findBeforeSortOrder(conversationId, beforeSortOrder, limit);
        } else {
            msgs = messageRepository.findLatestByConversationId(conversationId, limit);
        }

        boolean hasMore = false;
        if (!msgs.isEmpty()) {
            Integer oldestSortOrder = msgs.getFirst().getSortOrder();
            if (oldestSortOrder != null) {
                hasMore = messageRepository.countBeforeSortOrder(conversationId, oldestSortOrder) > 0;
            }
        }

        List<Message> deduped = deduplicateGenerations(msgs);
        Map<UUID, Integer> genCounts = preloadGenerationCounts(deduped);
        Map<UUID, Message> replyToMap = preloadReplyToMessages(deduped);
        List<MessageResponse> responses = deduped.stream()
                .map(m -> toMessageResponse(m, genCounts.get(m.getGenerationGroup()), replyToMap))
                .toList();
        return new MessagePageResponse(responses, hasMore);
    }

    /**
     * 获取指定消息的所有生成版本。
     */
    public List<MessageResponse> getGenerations(UUID accountId, UUID conversationId, UUID messageId) {
        conversationCrudService.findOwned(accountId, conversationId);
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (message.getGenerationGroup() == null) return List.of();
        return messageRepository.findByGenerationGroup(message.getGenerationGroup()).stream()
                .map(this::toMessageResponse).toList();
    }

    /**
     * 删除指定消息。
     */
    public void deleteMessage(UUID accountId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }
        messageRepository.delete(messageId);
    }

    /**
     * 统计对话消息数。
     */
    public int countMessages(UUID accountId, UUID conversationId) {
        conversationCrudService.findOwned(accountId, conversationId);
        return messageRepository.countByConversationId(conversationId);
    }

    /**
     * 为消息打分（点赞/点踩），点赞=分类正确，点踩=分类错误，触发意图分类持续学习。
     */
    public MessageResponse rateMessage(UUID accountId, UUID messageId, String rating) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("消息", messageId));
        if (!message.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("消息", messageId);
        }
        message.setRating(rating);
        message = messageRepository.save(message);

        // 反馈信号 → IntentClassifier 持续学习
        if ("dislike".equals(rating)) {
            intentClassifier.recordCorrection(messageId, false);
        } else if ("like".equals(rating)) {
            intentClassifier.recordCorrection(messageId, true);
        }

        return toMessageResponse(message);
    }

    /** 全文搜索消息（14-对话管理 §3）。 */
    public List<MessageResponse> searchMessages(UUID accountId, String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return messageRepository.search(accountId, keyword, page, size)
                .stream()
                .map(m -> toMessageResponse(m, null, java.util.Collections.emptyMap()))
                .toList();
    }

    // ============ 辅助方法 ============

    /**
     * 批量预查 generationGroup 消息数，避免 N+1。
     */
    public Map<UUID, Integer> preloadGenerationCounts(List<Message> messages) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Message msg : messages) {
            if (msg.getGenerationGroup() != null && !counts.containsKey(msg.getGenerationGroup())) {
                counts.put(msg.getGenerationGroup(), messageRepository.countByGenerationGroup(msg.getGenerationGroup()));
            }
        }
        return counts;
    }

    /**
     * 批量预查被引用消息，避免 N+1 且支持跨分页引用。
     */
    public Map<UUID, Message> preloadReplyToMessages(List<Message> messages) {
        Map<UUID, Message> replyToMap = new LinkedHashMap<>();
        for (Message msg : messages) {
            UUID replyToId = msg.getReplyToId();
            if (replyToId != null && !replyToMap.containsKey(replyToId)) {
                messageRepository.findById(replyToId).ifPresent(m -> replyToMap.put(replyToId, m));
            }
        }
        return replyToMap;
    }

    /**
     * 对于有 generationGroup 的消息，只保留每组中 generationIndex 最大的那条。
     */
    public List<Message> deduplicateGenerations(List<Message> messages) {
        Map<UUID, Message> latestPerGroup = new LinkedHashMap<>();
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getGenerationGroup() != null) {
                Message existing = latestPerGroup.get(msg.getGenerationGroup());
                if (existing == null) {
                    latestPerGroup.put(msg.getGenerationGroup(), msg);
                } else if (msg.getGenerationIndex() > existing.getGenerationIndex()
                        && msg.getStatus() == com.icusu.sivan.common.enums.MessageStatus.COMPLETED) {
                    // 新版本 COMPLETED 才替换，FAILED 版本不覆盖已有成功版本
                    latestPerGroup.put(msg.getGenerationGroup(), msg);
                } else if (msg.getGenerationIndex() > existing.getGenerationIndex()
                        && existing.getStatus() != com.icusu.sivan.common.enums.MessageStatus.COMPLETED) {
                    // 新旧都未完成，保留新版本（至少 index 更大）
                    latestPerGroup.put(msg.getGenerationGroup(), msg);
                }
            } else {
                result.add(msg);
            }
        }
        result.addAll(latestPerGroup.values());
        result.sort(Comparator.comparingInt(
                m -> m.getSortOrder() != null ? m.getSortOrder() : 0));
        return result;
    }

    /**
     * 创建助理消息占位（RUNNING 状态）。
     */
    public Message createAssistantMessage(Conversation conversation, UUID accountId) {
        return Message.builder()
                .conversationId(conversation.getConversationId())
                .accountId(accountId)
                .projectId(conversation.getProjectId())
                .role(Message.ROLE_ASSISTANT)
                .content("")
                .contentType("text")
                .status(MessageStatus.RUNNING)
                .build();
    }

    // ============ 响应映射 ============
    /**
     * 消息实体转为响应对象。
     */
    public MessageResponse toMessageResponse(Message message) {
        return toMessageResponse(message, null, java.util.Collections.emptyMap());
    }

    /**
     * 消息实体转为响应对象（含预查的 generationTotal 和 replyTo）。
     */
    public MessageResponse toMessageResponse(Message message, Integer preloadedGenTotal, Map<UUID, Message> replyToMap) {
        List<String> rawImages = MessageAttachmentsSerializer.deserializeImages(message.getImages());
        List<String> resolvedImages = rawImages != null ? resolveAttachmentUrls(rawImages) : null;
        List<String> rawAudios = MessageAttachmentsSerializer.deserializeAudios(message.getAudios());
        List<String> resolvedAudios = rawAudios != null ? resolveAttachmentUrls(rawAudios) : null;
        Integer genTotal = preloadedGenTotal != null ? preloadedGenTotal
                : (message.getGenerationGroup() != null ? messageRepository.countByGenerationGroup(message.getGenerationGroup()) : null);
        Map<String, String> replyTo = null;
        if (message.getReplyToId() != null) {
            Message ref = replyToMap.get(message.getReplyToId());
            if (ref != null) {
                replyTo = Map.of("role", ref.getRole(), "content", ref.getContent());
            }
        }
        return MessageResponse.builder()
                .messageId(message.getMessageId())
                .conversationId(message.getConversationId())
                .projectId(message.getProjectId())
                .role(message.getRole())
                .content(message.getContent())
                .contentType(message.getContentType())
                .thinking(message.getThinking())
                .targetAgent(message.getTargetAgent())
                .replyToId(message.getReplyToId())
                .replyTo(replyTo)
                .sortOrder(message.getSortOrder())
                .status(message.getStatus() != null ? message.getStatus().name() : null)
                .rating(message.getRating())
                .model(message.getModel())
                .totalTokens(message.getTotalTokens())
                .durationMs(message.getDurationMs())
                .thinkingDurationMs(message.getThinkingDurationMs())
                .thinkingTokens(message.getThinkingTokens())
                .generationIndex(message.getGenerationIndex())
                .generationGroup(message.getGenerationGroup())
                .generationTotal(genTotal)
                .images(resolvedImages)
                .audios(resolvedAudios)
                .attachments(MessageAttachmentsSerializer.deserializeAttachments(message.getAttachments()))
                .sections(parseSections(message.getSections()))
                .progress(parseProgress(message.getProgress()))
                .createdAt(message.getCreatedAt())
                .build();
    }

    /**
     * 将附件引用列表中的 fileId 转为 API 下载 URL。
     */
    public static List<String> resolveAttachmentUrls(List<String> uris) {
        if (uris == null) return null;
        List<String> result = new ArrayList<>(uris.size());
        for (String uri : uris) {
            if (uri == null || uri.startsWith("data:")) {
                result.add(uri);
            } else {
                try {
                    UUID.fromString(uri);
                    result.add("/api/files/" + uri);
                } catch (IllegalArgumentException e) {
                    result.add(uri);
                }
            }
        }
        return result;
    }

    /**
     * 解析 message.sections JSON 字符串为 List。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseSections(String sectionsJson) {
        if (sectionsJson == null || sectionsJson.isBlank()) return null;
        try {
            return objectMapper.readValue(sectionsJson, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 message.progress JSON 字符串为 Map。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseProgress(String progressJson) {
        if (progressJson == null || progressJson.isBlank()) return null;
        try {
            return objectMapper.readValue(progressJson, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
