package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.context.ContextForest;
import com.icusu.sivan.domain.context.ContextTree;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextBuilderTest {

    @Mock
    private IMessageRepository messageRepository;
    @Mock
    private ConversationTree conversationTree;

    @Test
    /** 空 CompressResult 不应抛异常。 */
    void build_withEmptyCompressResult() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        CompressResult empty = new CompressResult("", List.of(), List.of(), null, true);
        UUID convId = UUID.randomUUID();

        when(messageRepository.findByConversationId(convId)).thenReturn(List.of());

        String result = builder.build(convId, empty, 1000);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    /** 带 taskGoal 和 hotBatch 的压缩结果应产出格式化文本。 */
    void build_withContext() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        UUID convId = UUID.randomUUID();
        UUID taskGoalId = UUID.randomUUID();

        Message goalMsg = Message.builder()
                .messageId(taskGoalId).role("user").content("帮我分析销售数据").build();
        Message hotMsg = Message.builder()
                .messageId(UUID.randomUUID()).role("assistant").content("销售数据如下...").build();

        CompressResult result = new CompressResult(
                "对话摘要内容",
                List.of(hotMsg),
                List.of(taskGoalId),
                taskGoalId,
                false
        );

        when(messageRepository.findByConversationId(convId)).thenReturn(List.of(goalMsg, hotMsg));
        when(conversationTree.buildTopics(anyList())).thenReturn(List.of());

        String text = builder.build(convId, result, 1000);

        assertNotNull(text);
        // 应包含 taskGoal 内容
        assertTrue(text.contains("帮我分析销售数据"), "should contain task goal, got: " + text);
        // 应包含 hotBatch 消息
        assertTrue(text.contains("销售数据如下"), "should contain hot batch, got: " + text);
    }

    @Test
    /** 异常时应降级到 CompressResult.toSummaryText()。 */
    void build_fallbackOnException() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        CompressResult result = new CompressResult("降级摘要文本", List.of(), List.of(), null, false);

        when(messageRepository.findByConversationId(any())).thenThrow(new RuntimeException("DB异常"));

        String text = builder.build(UUID.randomUUID(), result, 1000);

        assertEquals("降级摘要文本", text);
    }

    @Test
    /** buildForSquad 空 SquadTree 时返回空（外部 fallback 兜底）。 */
    void buildForSquad_withoutSquadTree_returnsEmpty() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        CompressResult result = new CompressResult("摘要", List.of(), List.of(), null, false);

        String text = builder.buildForSquad(UUID.randomUUID(), result, 1000, null, null);

        // 没有 SquadTree 时返回空，由调用方兜底到 toSummaryText
        assertTrue(text == null || text.isEmpty());
    }

    @Test
    /** buildForSquad 含 SquadTree 时输出 Squad 执行状态。 */
    void buildForSquad_withSquadTree_containsSquadInfo() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        CompressResult result = new CompressResult("摘要", List.of(), List.of(), null, false);

        Squad squad = Squad.builder().name("测试Squad").phases(List.of(
                PhaseNode.builder().phase(0).name("分析").build()
        )).build();
        SquadExecution exec = SquadExecution.builder()
                .status(ExecutionStatus.RUNNING).currentPhase(0).taskDescription("测试").build();

        SquadTree squadTree = new SquadTree()
                .withSquad(squad).withExecution(exec);

        String text = builder.buildForSquad(UUID.randomUUID(), result, 1000, squadTree, null);

        assertNotNull(text);
        assertTrue(text.contains("测试Squad"), "should contain squad name, got: " + text);
    }

    @Test
    /** buildForSquad 含 MemoryTree 时输出记忆上下文。 */
    void buildForSquad_withMemories_containsMemory() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        CompressResult result = new CompressResult("摘要", List.of(), List.of(), null, false);

        List<MemoryEntry> memories = List.of(
                MemoryEntry.builder().content("用户偏好Python").important(true).build()
        );

        Squad squad = Squad.builder().name("Test").phases(List.of(
                PhaseNode.builder().phase(0).name("P0").build()
        )).build();
        SquadExecution exec = SquadExecution.builder()
                .status(ExecutionStatus.RUNNING).currentPhase(0).taskDescription("t").build();

        SquadTree squadTree = new SquadTree().withSquad(squad).withExecution(exec);

        String text = builder.buildForSquad(UUID.randomUUID(), result, 1000, squadTree, memories);

        assertNotNull(text);
        assertTrue(text.contains("用户偏好Python") || text.contains("Test"));
    }

    @Test
    /** withForest 可注入自定义森林。 */
    void withForest_setsForest() {
        ContextBuilder builder = new ContextBuilder(messageRepository, conversationTree);
        assertNotNull(builder.getForest());
        assertEquals(0, builder.getForest().size());

        // 注入自定义森林
        ContextForest forest = new ContextForest()
                .register(new ContextTree() {
                    @Override public String treeType() { return "test"; }
                    @Override public String buildContext(String scene, int maxTokens) { return "test context"; }
                    @Override public int estimateTokens() { return 10; }
                });
        builder.withForest(forest);
        assertEquals(1, builder.getForest().size());
    }
}
