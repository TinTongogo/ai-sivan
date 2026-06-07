package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.task.ExecutionShape;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import com.icusu.sivan.memory.pattern.ExplorationDecider;
import com.icusu.sivan.memory.pattern.FeatureExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecutionPathResolver 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ExecutionPathResolverTest {

    @Mock
    private InstinctPatternService instinctPatternService;
    @Mock
    private ExplorationDecider explorationDecider;
    @Mock
    private IInstinctPatternRepository patternRepository;

    private final FeatureExtractor featureExtractor = new FeatureExtractor();
    private final UUID accountId = UUID.randomUUID();
    private ExecutionPathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExecutionPathResolver(featureExtractor, instinctPatternService,
                explorationDecider, patternRepository);
    }

    /** 未匹配模板时返回 noMatch。 */
    @Test
    void noMatch_shouldReturnNoMatch() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.empty());

        ExecutionPathResult result = resolver.resolve("写一段简单的代码", accountId);

        assertFalse(result.fromTemplate());
        assertFalse(result.shouldSkipClassify());
        assertNull(result.executionPath());
        assertNull(result.patternId());
    }

    /** 匹配模板且不探索时返回模板路径。 */
    @Test
    void templateMatch_noExploration_shouldReturnExecutionPath() {
        InstinctPattern pattern = squadPattern();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.of(pattern));
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));
        when(explorationDecider.shouldExplore(accountId, 1)).thenReturn(false);

        ExecutionPathResult result = resolver.resolve("写一段复杂的代码", accountId);

        assertTrue(result.fromTemplate());
        assertTrue(result.shouldSkipClassify());
        assertNotNull(result.executionPath());
        assertEquals(ExecutionShape.SQUAD, result.executionPath().shape());
        assertEquals(pattern.getPatternId(), result.patternId());
    }

    /** 匹配模板但触发探索时应不跳过 classify。 */
    @Test
    void templateMatch_withExploration_shouldNotSkipClassify() {
        InstinctPattern pattern = squadPattern();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.of(pattern));
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));
        when(explorationDecider.shouldExplore(accountId, 1)).thenReturn(true);

        ExecutionPathResult result = resolver.resolve("分析一下数据集", accountId);

        assertTrue(result.fromTemplate());
        assertFalse(result.shouldSkipClassify());
        assertNotNull(result.executionPath());
        assertEquals(pattern.getPatternId(), result.patternId());
    }

    /** CHAT 模式的模板返回 CHAT 执行路径。 */
    @Test
    void templateMatch_chatMode_shouldReturnChatPath() {
        InstinctPattern pattern = chatPattern();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.of(pattern));
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));
        when(explorationDecider.shouldExplore(accountId, 1)).thenReturn(false);

        ExecutionPathResult result = resolver.resolve("你好", accountId);

        assertTrue(result.shouldSkipClassify());
        assertEquals(ExecutionShape.CHAT, result.executionPath().shape());
    }

    /** SINGLE_AGENT 模式的模板返回对应执行路径。 */
    @Test
    void templateMatch_singleAgentMode_shouldReturnSingleAgentPath() {
        InstinctPattern pattern = singleAgentPattern();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.of(pattern));
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));
        when(explorationDecider.shouldExplore(accountId, 1)).thenReturn(false);

        ExecutionPathResult result = resolver.resolve("帮我查一下天气", accountId);

        assertTrue(result.shouldSkipClassify());
        assertEquals(ExecutionShape.SINGLE_AGENT, result.executionPath().shape());
    }

    /** 空文本应返回无匹配。 */
    @Test
    void blankInput_shouldReturnNoMatch() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId)))
                .thenReturn(Optional.empty());

        ExecutionPathResult result = resolver.resolve("", accountId);

        assertFalse(result.fromTemplate());
        assertNull(result.executionPath());
    }

    // ===== 辅助方法 =====

    private InstinctPattern squadPattern() {
        var p = new InstinctPattern();
        p.setPatternId(UUID.randomUUID());
        p.setAccountId(accountId);
        p.setExecutionMode("SQUAD");
        p.setTopologyJson("[{\"phase\":0,\"name\":\"执行\"}]");
        p.setActive(true);
        p.setVersion(1);
        p.setFeatureVector(PatternFeatureVector.fromTaskFeatures(
                new TaskFeatures(TaskFeatures.Complexity.LEVEL_3,
                        TaskFeatures.Dependency.SEQUENTIAL,
                        TaskFeatures.InputStructure.FREE_TEXT,
                        TaskFeatures.Domain.CODING,
                        TaskFeatures.OutputType.CODE)));
        return p;
    }

    private InstinctPattern chatPattern() {
        var p = new InstinctPattern();
        p.setPatternId(UUID.randomUUID());
        p.setAccountId(accountId);
        p.setExecutionMode("CHAT");
        p.setActive(true);
        p.setVersion(1);
        p.setFeatureVector(PatternFeatureVector.fromTaskFeatures(
                new TaskFeatures(TaskFeatures.Complexity.LEVEL_1,
                        TaskFeatures.Dependency.INDEPENDENT,
                        TaskFeatures.InputStructure.Q_A,
                        TaskFeatures.Domain.GENERAL,
                        TaskFeatures.OutputType.SHORT_TEXT)));
        return p;
    }

    private InstinctPattern singleAgentPattern() {
        var p = new InstinctPattern();
        p.setPatternId(UUID.randomUUID());
        p.setAccountId(accountId);
        p.setExecutionMode("SINGLE_AGENT");
        p.setActive(true);
        p.setVersion(1);
        p.setFeatureVector(PatternFeatureVector.fromTaskFeatures(
                new TaskFeatures(TaskFeatures.Complexity.LEVEL_2,
                        TaskFeatures.Dependency.INDEPENDENT,
                        TaskFeatures.InputStructure.Q_A,
                        TaskFeatures.Domain.GENERAL,
                        TaskFeatures.OutputType.SHORT_TEXT)));
        return p;
    }
}
