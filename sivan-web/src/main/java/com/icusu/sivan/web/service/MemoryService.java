package com.icusu.sivan.web.memory.service;

import com.icusu.sivan.agent.prompt.MemoryPrompts;
import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.memory.curve.EbbinghausForgettingCurve;
import com.icusu.sivan.web.conversation.service.message.MessageAttachmentsSerializer;
import com.icusu.sivan.web.memory.dto.CreateMemoryRequest;
import com.icusu.sivan.web.memory.dto.MemoryResponse;
import com.icusu.sivan.web.memory.dto.UpdateMemoryRequest;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 记忆服务，管理智能体记忆条目的创建、查询与维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final IMemoryRepository memoryRepository;
    private final ModelRouter modelRouter;
    private final IMessageRepository messageRepository;
    private final IConversationRepository conversationRepository;
    private final FileStoragePort fileStoragePort;
    private final IEmbeddingService embeddingService;
    private final MemoryConsolidator memoryConsolidator;

    /**
     * 从对话中提取用户核心需求并创建会话级记忆条目。
     * 在 LLM 流完成后自动调用。同时记录用户消息和 AI 回复，生成交换级摘要。
     *
     * @param accountId      当前用户 ID
     * @param conversationId 对话 ID
     * @param projectId      项目 ID
     * @param assistantMsg   LLM 回复消息（status 必须为 COMPLETED）
     */
    public void createFromConversation(UUID accountId, UUID conversationId, UUID projectId, Message assistantMsg) {
        // 获取最近一条用户消息
        var userMsgOpt = messageRepository.findLatestUserMessage(conversationId);
        if (userMsgOpt.isEmpty()) {
            log.debug("跳过记忆生成：会话 {} 无用户消息", conversationId);
            return;
        }
        Message userMessage = userMsgOpt.get();
        String userContent = userMessage.getContent();
        String assistantContent = assistantMsg.getContent();

        // 生成交换级摘要后异步持久化记忆
        extractExchangeSummary(accountId, userContent, assistantContent)
                .subscribe(summary -> {
                    try {
                        // 记录完整交换内容：用户消息 + AI 回复
                        String exchangeContent = userContent;
                        if (assistantContent != null && !assistantContent.isBlank()) {
                            exchangeContent += "\n\n" + assistantContent;
                        }

                        // 记录 AI 回复到 metadata
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        if (assistantContent != null && !assistantContent.isBlank()) {
                            metadata.put("reply", assistantContent);
                        }

                        // 判断是否为对话的第一轮交换（尚无记忆），自动标记为重要以保护任务上下文
                        List<MemoryEntry> existingMemories = memoryRepository.findByLevelAndScope(
                                accountId, MemoryLevel.SESSION, conversationId.toString());
                        boolean isFirstExchange = existingMemories.isEmpty();

                        // 多模态向量化：用户消息含图片时计算图文向量
                        float[] multiModalVec = computeMultimodalVector(exchangeContent, userMessage, accountId);

                        var now = LocalDateTime.now(ZoneOffset.UTC);
                        MemoryEntry entry = MemoryEntry.builder()
                                .accountId(accountId)
                                .projectId(projectId)
                                .level(MemoryLevel.SESSION)
                                .scopeId(conversationId.toString())
                                .content(exchangeContent)
                                .metadata(metadata)
                                .summary(summary)
                                .vector(multiModalVec)
                                .retention(1.0f)
                                .accessCount(1)
                                .lastAccessedAt(now)
                                .archived(false)
                                .important(isFirstExchange)
                                .build();

                        memoryRepository.save(entry);
                        log.debug("会话记忆已生成: conversationId={}, summary={}", conversationId, summary);

                        // 尝试合并相似记忆（异步安全，失败不阻塞）
                        try {
                            memoryConsolidator.consolidate(accountId, conversationId);
                        } catch (Exception e) {
                            log.debug("记忆合并跳过(不影响主流程): {}", e.getMessage());
                        }
                    } catch (Exception e) {
                        log.warn("会话记忆生成失败: conversationId={}", conversationId, e);
                    }
                }, err -> log.error("记忆持久化失败", err));
    }

    /**
     * 计算多模态向量：当用户消息包含图片时，将文本 + 首张图片一起向量化。
     * 无图片或向量化失败时返回 null，适配器将自动 fallback 到 text-only embedding。
     */
    private float[] computeMultimodalVector(String exchangeContent, Message userMessage, UUID accountId) {
        try {
            String imagesJson = userMessage.getImages();
            if (imagesJson == null || imagesJson.isBlank()) return null;

            List<String> imageRefs = MessageAttachmentsSerializer.deserializeImages(imagesJson);
            if (imageRefs == null || imageRefs.isEmpty()) return null;

            String firstImage = imageRefs.get(0);
            String imageBase64;
            if (firstImage.startsWith("data:")) {
                imageBase64 = firstImage;
            } else {
                imageBase64 = fileStoragePort.resolveToBase64(accountId, UUID.fromString(firstImage));
            }
            return embeddingService.embedWithImage(exchangeContent, imageBase64);
        } catch (Exception e) {
            log.warn("多模态向量化失败（降级为 text-only）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 LLM 从用户消息 + AI 回复提取交换级摘要（≤80 字）。
     */
    private Mono<String> extractExchangeSummary(UUID accountId, String userContent, String assistantContent) {
        if (userContent == null || userContent.isBlank()) return Mono.just("");
        String userPrompt = MemoryPrompts.exchangeSummary(userContent, assistantContent).content();
        List<Msg> msgs = List.of(
                Msg.of(Role.USER, userPrompt)
        );
        return modelRouter.getDefaultModel(accountId).chat(msgs, Model.ModelParams.defaults())
                .map(response -> {
                    String result = response.msg().text();
                    if (result != null) {
                        result = result.strip();
                        if (result.length() > 80) {
                            result = result.substring(0, 77) + "...";
                        }
                    }
                    return result != null ? result : "";
                })
                .onErrorReturn("");
    }

    /**
     * 获取对话的 SESSION 级记忆条目（供上下文压缩消费）。
     * 返回领域实体而非 DTO，调用方不应将其暴露到 API 层。
     * 每次访问自动递增 accessCount，延长遗忘曲线有效半衰期。
     */
    public List<MemoryEntry> getSessionMemories(UUID accountId, UUID conversationId) {
        List<MemoryEntry> entries = memoryRepository.findByLevelAndScope(accountId, MemoryLevel.SESSION, conversationId.toString());
        touchMemories(entries);
        return entries;
    }

    /**
     * 获取当前用户的长期记忆（USER 级），供上下文压缩注入。
     * 返回未归档的 USER 级记忆条目，用于跨会话持久化用户上下文。
     * 每次访问自动递增 accessCount，延长遗忘曲线有效半衰期。
     */
    public List<MemoryEntry> getUserMemories(UUID accountId) {
        List<MemoryEntry> entries = memoryRepository.findByLevel(accountId, MemoryLevel.USER);
        touchMemories(entries);
        return entries;
    }

    /**
     * 递增记忆条目的访问计数并更新最后访问时间（间隔重复效应）。
     * 每次读取记忆时调用，使遗忘曲线的有效半衰期随访问次数延长。
     */
    private void touchMemories(List<MemoryEntry> entries) {
        if (entries.isEmpty()) return;
        var now = LocalDateTime.now(ZoneOffset.UTC);
        for (MemoryEntry entry : entries) {
            entry.setAccessCount((entry.getAccessCount() != null ? entry.getAccessCount() : 0) + 1);
            entry.setLastAccessedAt(now);
            memoryRepository.update(entry);
        }
    }

    /**
     * 创建记忆条目。
     */
    public MemoryResponse create(UUID accountId, CreateMemoryRequest request) {
        MemoryEntry entry = MemoryEntry.builder()
                .accountId(accountId)
                .projectId(request.getProjectId())
                .level(request.getLevel() != null ? MemoryLevel.valueOf(request.getLevel()) : MemoryLevel.SESSION)
                .scopeId(request.getScopeId())
                .content(request.getContent())
                .summary(request.getSummary())
                .important(request.getImportant() != null && request.getImportant())
                .retention(1.0f)
                .accessCount(1)
                .archived(false)
                .build();

        UUID memoryId = memoryRepository.save(entry);
        entry.setMemoryId(memoryId);
        return toResponse(entry);
    }

    /**
     * 根据 ID 查询记忆条目。
     */
    public MemoryResponse getById(UUID accountId, UUID memoryId) {
        MemoryEntry entry = findOwned(accountId, memoryId);
        return toResponse(entry);
    }

    /**
     * 查询记忆列表，可按级别和作用域筛选。page=0 时不走分页返回全部。
     */
    public List<MemoryResponse> list(UUID accountId, String level, String scopeId) {
        List<MemoryEntry> entries;
        if (level != null && scopeId != null) {
            entries = memoryRepository.findByLevelAndScope(accountId, MemoryLevel.valueOf(level), scopeId);
        } else {
            entries = memoryRepository.findAllByAccount(accountId);
        }
        return entries.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 分页查询记忆列表，可按层级和关键词筛选。
     */
    public PageResponse<MemoryResponse> listPage(UUID accountId, int page, int size, String level, String keyword) {
        List<MemoryEntry> allEntries;
        long total;
        if (keyword != null && !keyword.isBlank()) {
            allEntries = memoryRepository.searchByKeyword(accountId, keyword, page, size);
            total = memoryRepository.countByKeyword(accountId, keyword);
        } else {
            allEntries = memoryRepository.findAllByAccountPage(accountId, level, page, size);
            total = memoryRepository.countByAccount(accountId, level);
        }
        // 批量解析 SESSION 级 scopeId → 对话标题
        Map<String, String> convNames = resolveConversationNames(allEntries);
        List<MemoryResponse> items = allEntries.stream()
                .map(e -> toResponse(e, convNames))
                .toList();
        return PageResponse.of(items, total, page + 1, size);
    }

    /**
     * 批量查询 SESSION 级 scopeId 对应的对话标题。
     */
    private Map<String, String> resolveConversationNames(List<MemoryEntry> entries) {
        Map<String, String> names = new LinkedHashMap<>();
        for (MemoryEntry e : entries) {
            if (e.getLevel() == MemoryLevel.SESSION && e.getScopeId() != null) {
                names.put(e.getScopeId(), null);
            }
        }
        if (names.isEmpty()) return names;
        for (String scopeId : names.keySet()) {
            try {
                conversationRepository.findById(UUID.fromString(scopeId))
                        .ifPresent(c -> names.put(scopeId, c.getTitle()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return names;
    }

    /**
     * 基于遗忘曲线判断记忆是否仍有效（保留率 ≥ 归档阈值）。
     * 已归档或遗忘的记忆对列表展示不可见。
     */
    private boolean isRetained(MemoryEntry entry) {
        if (Boolean.TRUE.equals(entry.getArchived())) return false;
        if (entry.getLevel() == null || entry.getLastAccessedAt() == null) return true;
        int access = entry.getAccessCount() != null ? entry.getAccessCount() : 0;
        double retention = EbbinghausForgettingCurve.calculateRetentionWithAccess(
                entry.getLevel(), entry.getLastAccessedAt(), Math.max(access, 1), LocalDateTime.now(ZoneOffset.UTC));
        return retention >= EbbinghausForgettingCurve.ARCHIVE_THRESHOLD;
    }

    /**
     * 更新记忆条目。
     */
    public MemoryResponse update(UUID accountId, UUID memoryId, UpdateMemoryRequest request) {
        MemoryEntry entry = findOwned(accountId, memoryId);
        entry.updateFrom(request.getContent(), request.getSummary(), request.getImportant(),
                request.getRetention(), request.getArchived());
        memoryRepository.update(entry);
        return toResponse(entry);
    }

    /**
     * 删除记忆条目。
     */
    @Transactional
    public void delete(UUID accountId, UUID memoryId) {
        MemoryEntry entry = findOwned(accountId, memoryId);
        memoryRepository.delete(entry.getMemoryId());
    }

    /**
     * 批量删除记忆（校验所有权后删除）。
     */
    @Transactional
    public void deleteBatch(java.util.List<UUID> ids, UUID accountId) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            MemoryEntry entry = findOwned(accountId, id);
            memoryRepository.delete(entry.getMemoryId());
        }
    }

    /**
     * 切换记忆的重要标记。
     */
    public MemoryResponse toggleImportant(UUID accountId, UUID memoryId) {
        MemoryEntry entry = findOwned(accountId, memoryId);
        entry.setImportant(!entry.getImportant());
        memoryRepository.update(entry);
        return toResponse(entry);
    }

    /**
     * 查询重要记忆列表。
     */
    public List<MemoryResponse> listImportant(UUID accountId, Integer limit) {
        int topN = limit != null ? limit : 20;
        List<MemoryEntry> entries = memoryRepository.findImportant(accountId, null, topN);
        return entries.stream().map(this::toResponse).toList();
    }

    /**
     * 获取记忆统计信息。
     */
    public Map<String, Object> getStats(UUID accountId) {
        long total = memoryRepository.countByAccount(accountId);
        long important = memoryRepository.countImportantByAccount(accountId);
        long archived = memoryRepository.countArchivedByAccount(accountId);
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", total);
        stats.put("importantCount", important);
        stats.put("archivedCount", archived);
        return stats;
    }

    /**
     * 查找当前用户拥有的记忆条目。
     */
    private MemoryEntry findOwned(UUID accountId, UUID memoryId) {
        MemoryEntry entry = memoryRepository.findByIdAndAccount(memoryId, accountId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("记忆条目", memoryId));
        return entry;
    }

    /**
     * 转换为响应对象。
     */
    private MemoryResponse toResponse(MemoryEntry entry) {
        return toResponse(entry, java.util.Collections.emptyMap());
    }

    private MemoryResponse toResponse(MemoryEntry entry, Map<String, String> convNames) {
        String scopeName = null;
        if (entry.getLevel() == MemoryLevel.SESSION && entry.getScopeId() != null) {
            scopeName = convNames.get(entry.getScopeId());
        }
        return MemoryResponse.builder()
                .memoryId(entry.getMemoryId())
                .projectId(entry.getProjectId())
                .level(entry.getLevel() != null ? entry.getLevel().name() : null)
                .scopeId(entry.getScopeId())
                .scopeName(scopeName)
                .content(entry.getContent())
                .retention(entry.getRetention())
                .accessCount(entry.getAccessCount())
                .archived(entry.getArchived())
                .important(entry.getImportant())
                .summary(entry.getSummary())
                .createdAt(entry.getCreatedAt())
                .lastAccessedAt(entry.getLastAccessedAt())
                .build();
    }
}
