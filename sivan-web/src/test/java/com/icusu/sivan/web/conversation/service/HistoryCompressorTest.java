package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelResponse;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.web.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryCompressorTest {

    @Mock
    private IMessageRepository messageRepository;
    @Mock
    private MemoryService memoryService;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private Model model;

    private HistoryCompressor compressor;
    private final UUID conversationId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private Message userMsg(String content) {
        return Message.builder().role("user").content(content).build();
    }

    private Message assistantMsg(String content) {
        return Message.builder().role("assistant").content(content).build();
    }

    private MemoryEntry sessionMemory(String summary, boolean important, int accessCount) {
        return MemoryEntry.builder()
                .level(MemoryLevel.SESSION)
                .scopeId(conversationId.toString())
                .summary(summary)
                .important(important)
                .accessCount(accessCount)
                .lastAccessedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @BeforeEach
    void setUp() {
        compressor = new HistoryCompressor(messageRepository, memoryService, modelRouter);
    }

    /** No-op progress consumer for tests. */
    private static final java.util.function.Consumer<String> NOOP_PROGRESS = msg -> {};

    // ========== 边界条件 ==========

    @Nested
    class EdgeCaseTest {
        @Test
        void emptyMessages_returnsEmptyResult() {
            when(messageRepository.findByConversationId(conversationId)).thenReturn(List.of());

            String text = compressor.compressStream(conversationId, 1000, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            assertTrue(text.isEmpty());
        }

        @Test
        void onlyNonUserAssistantMessages_returnsEmpty() {
            Message systemMsg = Message.builder().role("system").content("system msg").build();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(List.of(systemMsg));

            String text = compressor.compressStream(conversationId, 1000, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertTrue(text == null || text.isEmpty());
        }

        @Test
        void withinBudget_returnsFullMessagesNotCompressed() {
            List<Message> msgs = List.of(userMsg("hi"), assistantMsg("hello"));
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);

            String text = compressor.compressStream(conversationId, 1000, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            assertTrue(text.contains("对话历史"));
        }
    }

    // ========== 压缩场景 ==========

    @Nested
    class CompressionTest {
        @Test
        void overBudget_triggersCompression() {
            List<Message> msgs = List.of(
                    userMsg("a".repeat(200)), assistantMsg("b".repeat(200)),
                    userMsg("c".repeat(200)), assistantMsg("d".repeat(200)),
                    userMsg("e".repeat(200)), assistantMsg("f".repeat(200))
            );
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(List.of());

            String text = compressor.compressStream(conversationId, 200, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            assertTrue(text.contains("近期对话"));
        }

        @Test
        void warmSummaryIncludesImportantEntriesFirst() {
            List<Message> msgs = List.of(
                    userMsg("a".repeat(200)), assistantMsg("b".repeat(200))
            );
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(List.of(
                    sessionMemory("次要信息", false, 1),
                    sessionMemory("重要决策", true, 5)
            ));

            String text = compressor.compressStream(conversationId, 50, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            assertTrue(text.contains("重要决策"));
        }

        @Test
        void cutoffEnsuresMinRecentPairs() {
            List<Message> msgs = List.of(
                    userMsg("old1"), assistantMsg("old2"),
                    userMsg("mid1"), assistantMsg("mid2"),
                    userMsg("recent1"), assistantMsg("recent2")
            );
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(List.of());

            String text = compressor.compressStream(conversationId, 10, accountId, NOOP_PROGRESS).block().toSummaryText();

            // MIN_RECENT_PAIRS = 2 → 至少保留 4 条最新消息
            assertNotNull(text);
            assertTrue(text.contains("recent1"));
            assertTrue(text.contains("recent2"));
        }
    }

    // ========== COLD 层 ==========

    @Nested
    class ColdLayerTest {
        private List<Message> overBudgetMessages() {
            return List.of(
                    userMsg("a".repeat(200)), assistantMsg("b".repeat(200)),
                    userMsg("c".repeat(200)), assistantMsg("d".repeat(200))
            );
        }

        @Test
        void manyMemories_triggersCold() {
            List<Message> msgs = overBudgetMessages();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(
                    buildMemories(55));
            Msg mockMsg = mock(Msg.class);
            when(mockMsg.text()).thenReturn("{\"user_profile\":\"...\"}");
            when(modelRouter.getDefaultModel(any())).thenReturn(model);
            when(model.chat(anyList(), any())).thenReturn(Mono.just(new ModelResponse(mockMsg, null)));

            String text = compressor.compressStream(conversationId, 50, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            verify(model).chat(anyList(), any());
        }

        @Test
        void fewMemories_skipsCold() {
            List<Message> msgs = overBudgetMessages();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(
                    buildMemories(10));

            String text = compressor.compressStream(conversationId, 50, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            verify(model, never()).chat(anyList(), any());
        }

        @Test
        void coldLlmFails_fallsBackToRaw() {
            List<Message> msgs = overBudgetMessages();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(
                    buildMemories(55));
            when(modelRouter.getDefaultModel(any())).thenReturn(model);
            when(model.chat(anyList(), any()))
                    .thenReturn(Mono.error(new RuntimeException("LLM 服务不可用")));

            String text = compressor.compressStream(conversationId, 50, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            assertTrue(text.contains("记忆条目")); // fallback text
        }

        @Test
        void warmSufficient_skipsColdEvenWithManyMemories() {
            List<Message> msgs = overBudgetMessages();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(
                    buildMemoriesWithLongSummaries(55));

            String text = compressor.compressStream(conversationId, 200, accountId, NOOP_PROGRESS).block().toSummaryText();

            assertNotNull(text);
            verify(model, never()).chat(anyList(), any());
        }
    }

    // ========== 边界值：NeedsCold ==========

    @Nested
    class NeedsColdTest {
        private List<Message> overBudgetMessages() {
            return List.of(
                    userMsg("a".repeat(200)), assistantMsg("b".repeat(200)),
                    userMsg("c".repeat(200)), assistantMsg("d".repeat(200))
            );
        }

        @Test
        void warmBlank_needsColdTriggered() {
            List<Message> msgs = overBudgetMessages();
            when(messageRepository.findByConversationId(conversationId)).thenReturn(msgs);
            when(memoryService.getSessionMemories(accountId, conversationId)).thenReturn(
                    buildMemoriesWithEmptySummaries(55));

            String text = compressor.compressStream(conversationId, 50, accountId, NOOP_PROGRESS).block().toSummaryText();

            // Cold triggered but summaries empty → LLM not called
            assertNotNull(text);
            verify(model, never()).chat(anyList(), any());
        }
    }

    // ========== 辅助方法 ==========

    private List<MemoryEntry> buildMemories(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> sessionMemory("记忆条目 " + i, false, 0))
                .toList();
    }

    private List<MemoryEntry> buildMemoriesWithLongSummaries(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> sessionMemory(
                        "这是一条较长的记忆条目用来填充预算确保 warmSummary 不触发 cold " + i,
                        false, 0))
                .toList();
    }

    private List<MemoryEntry> buildMemoriesWithEmptySummaries(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> sessionMemory("", false, 0))
                .toList();
    }
}
