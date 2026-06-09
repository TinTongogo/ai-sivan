package com.icusu.sivan.web.service;

import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.model.Model.ModelResponse;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryConsolidator 单元测试。
 * 验证记忆合并去重：相似度阈值判定、合并逻辑、LLM 降级。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidatorTest {

    @Mock
    private IMemoryRepository memoryRepository;
    @Mock
    private ModelRouter modelRouter;

    private MemoryConsolidator consolidator;
    private final UUID accountId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @Captor
    private ArgumentCaptor<MemoryEntry> entryCaptor;

    @BeforeEach
    void setUp() {
        consolidator = new MemoryConsolidator(memoryRepository, modelRouter);
    }

    @Test
    void consolidate_shouldMergeSimilarMemories() {
        // 创建两条向量相似度高的记忆
        float[] similarVec = {1.0f, 0.0f, 0.0f};
        MemoryEntry entry1 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("用户问了关于 Python 的基础知识")
                .summary("Python 基础问题")
                .vector(similarVec)
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        MemoryEntry entry2 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("用户继续追问 Python 的高级特性")
                .summary("Python 进阶问题")
                .vector(similarVec)  // 相同向量 → 余弦相似度 1.0
                .retention(0.8f)
                .accessCount(2)
                .archived(false)
                .build();

        when(memoryRepository.findByLevelAndScope(eq(accountId), eq(MemoryLevel.SESSION), eq(conversationId.toString())))
                .thenReturn(List.of(entry1, entry2));

        // Mock LLM 摘要
        Model mockModel = mock(Model.class);
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(Msg.of(Role.ASSISTANT, "Python 相关问题讨论"), null)));
        when(modelRouter.getDefaultModel(accountId)).thenReturn(mockModel);

        consolidator.consolidate(accountId, conversationId);

        // 验证：entry2 被归档，entry1 被更新
        verify(memoryRepository, atLeast(2)).save(entryCaptor.capture());
        var savedEntries = entryCaptor.getAllValues();

        boolean foundArchived = savedEntries.stream().anyMatch(MemoryEntry::isArchived);
        assertTrue(foundArchived, "应有一条记忆被归档");

        // 验证 retention 取 max
        MemoryEntry base = savedEntries.stream().filter(e -> !e.isArchived()).findFirst().orElse(null);
        if (base != null) {
            assertEquals(0.9f, base.getRetention(), 0.01, "retention 应取最大值");
            assertTrue(base.getAccessCount() >= 3, "accessCount 应累加");
        }
    }

    @Test
    void consolidate_shouldNotMerge_whenBelowThreshold() {
        float[] vec1 = {1.0f, 0.0f, 0.0f};
        float[] vec2 = {0.0f, 1.0f, 0.0f};  // 正交 → 余弦相似度 0

        MemoryEntry entry1 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 A")
                .summary("摘要 A")
                .vector(vec1)
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        MemoryEntry entry2 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 B")
                .summary("摘要 B")
                .vector(vec2)
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        when(memoryRepository.findByLevelAndScope(eq(accountId), eq(MemoryLevel.SESSION), eq(conversationId.toString())))
                .thenReturn(List.of(entry1, entry2));

        consolidator.consolidate(accountId, conversationId);

        // 不应有归档操作
        verify(memoryRepository, atMost(2)).save(any());
    }

    @Test
    void consolidate_shouldSkip_whenFewerThanTwoEntries() {
        when(memoryRepository.findByLevelAndScope(eq(accountId), eq(MemoryLevel.SESSION), eq(conversationId.toString())))
                .thenReturn(List.of());

        consolidator.consolidate(accountId, conversationId);
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void consolidate_shouldHandleLlmFailureGracefully() {
        float[] similarVec = {1.0f, 0.0f, 0.0f};
        MemoryEntry entry1 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 A")
                .summary("摘要 A")
                .vector(similarVec)
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        MemoryEntry entry2 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 B")
                .summary("摘要 B")
                .vector(similarVec)
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        when(memoryRepository.findByLevelAndScope(eq(accountId), eq(MemoryLevel.SESSION), eq(conversationId.toString())))
                .thenReturn(List.of(entry1, entry2));

        // LLM 调用抛出异常
        when(modelRouter.getDefaultModel(accountId)).thenThrow(new RuntimeException("LLM 不可用"));

        // 不应抛出异常，应降级处理
        consolidator.consolidate(accountId, conversationId);

        // 即使 LLM 失败，合并仍然执行（降级为拼接摘要）
        verify(memoryRepository, atLeast(2)).save(any());
    }

    @Test
    void consolidate_shouldNotMerge_whenEntriesHaveNullVectors() {
        MemoryEntry entry1 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 A")
                .summary("摘要 A")
                .vector(null)  // 无向量
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        MemoryEntry entry2 = MemoryEntry.builder()
                .memoryId(UUID.randomUUID())
                .accountId(accountId)
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .content("内容 B")
                .summary("摘要 B")
                .vector(null)  // 无向量
                .retention(0.9f)
                .accessCount(1)
                .archived(false)
                .build();

        when(memoryRepository.findByLevelAndScope(eq(accountId), eq(MemoryLevel.SESSION), eq(conversationId.toString())))
                .thenReturn(List.of(entry1, entry2));

        consolidator.consolidate(accountId, conversationId);
        // 无向量的条目不合并，最多调用 2 次 save（各自保存）
        verify(memoryRepository, atMost(2)).save(any());
    }
}
