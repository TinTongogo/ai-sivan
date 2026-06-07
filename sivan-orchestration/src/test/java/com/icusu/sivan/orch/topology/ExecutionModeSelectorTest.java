package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.task.ExecutionPath;
import com.icusu.sivan.domain.task.TaskFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionModeSelectorTest {

    @Test
    void level1ShouldReturnChat() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("CHAT", path.shape().name());
    }

    @Test
    void level1WithAnyDependencyShouldReturnChat() {
        for (var dep : TaskFeatures.Dependency.values()) {
            var f = new TaskFeatures(
                    TaskFeatures.Complexity.LEVEL_1, dep,
                    TaskFeatures.InputStructure.FREE_TEXT,
                    TaskFeatures.Domain.GENERAL,
                    TaskFeatures.OutputType.SHORT_TEXT);
            assertEquals("CHAT", ExecutionModeSelector.recommend(f).shape().name(),
                    "LEVEL_1 + " + dep + " 应返回 CHAT");
        }
    }

    @Test
    void level2IndependentShouldReturnSingleAgent() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_2,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.Q_A,
                TaskFeatures.Domain.ANALYSIS,
                TaskFeatures.OutputType.SHORT_TEXT);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SINGLE_AGENT", path.shape().name());
    }

    @Test
    void level2ConditionalShouldReturnSquadConditional() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_2,
                TaskFeatures.Dependency.CONDITIONAL,
                TaskFeatures.InputStructure.STRUCTURED_DATA,
                TaskFeatures.Domain.RESEARCH,
                TaskFeatures.OutputType.DECISION);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("CONDITIONAL", path.squadMode());
    }

    @Test
    void level3DefaultShouldReturnSquadSequential() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("SEQUENTIAL", path.squadMode());
    }

    @Test
    void level3ParallelShouldReturnSquadParallel() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.PARALLEL,
                TaskFeatures.InputStructure.MULTI_MODAL,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.JSON);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("PARALLEL", path.squadMode());
    }

    @Test
    void level3ConditionalShouldReturnSquadConditional() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.CONDITIONAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.ANALYSIS,
                TaskFeatures.OutputType.DECISION);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("CONDITIONAL", path.squadMode());
    }

    @Test
    void level4DefaultShouldReturnHierarchical() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_4,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.CODE,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.CODE);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("HIERARCHICAL", path.squadMode());
    }

    @Test
    void level4ParallelShouldReturnHierarchicalParallel() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_4,
                TaskFeatures.Dependency.PARALLEL,
                TaskFeatures.InputStructure.STRUCTURED_DATA,
                TaskFeatures.Domain.CREATIVE,
                TaskFeatures.OutputType.MULTI_MODAL);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("HIERARCHICAL", path.squadMode());
        assertEquals("PARALLEL", path.phaseMode());
    }

    @Test
    void level5ShouldReturnHierarchicalParallel() {
        var f = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_5,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.MULTI_MODAL,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.MULTI_MODAL);
        ExecutionPath path = ExecutionModeSelector.recommend(f);
        assertEquals("SQUAD", path.shape().name());
        assertEquals("HIERARCHICAL", path.squadMode());
        assertEquals("PARALLEL", path.phaseMode());
    }

    @Test
    void nullFeaturesShouldReturnSquad() {
        ExecutionPath path = ExecutionModeSelector.recommend(null);
        assertEquals("SQUAD", path.shape().name());
        assertNotNull(path.reason());
    }

    @Test
    void all20CombinationsShouldProduceValidPath() {
        for (var c : TaskFeatures.Complexity.values()) {
            for (var d : TaskFeatures.Dependency.values()) {
                var f = new TaskFeatures(c, d,
                        TaskFeatures.InputStructure.FREE_TEXT,
                        TaskFeatures.Domain.GENERAL,
                        TaskFeatures.OutputType.SHORT_TEXT);
                ExecutionPath path = ExecutionModeSelector.recommend(f);
                assertNotNull(path);
                assertNotNull(path.shape());
                assertNotNull(path.reason());
                assertFalse(path.reason().isBlank());
            }
        }
    }

    @Test
    void reasonIsNotEmptyForAllCombinations() {
        for (var c : TaskFeatures.Complexity.values()) {
            for (var d : TaskFeatures.Dependency.values()) {
                var f = new TaskFeatures(c, d,
                        TaskFeatures.InputStructure.FREE_TEXT,
                        TaskFeatures.Domain.GENERAL,
                        TaskFeatures.OutputType.SHORT_TEXT);
                String reason = ExecutionModeSelector.recommend(f).reason();
                assertNotNull(reason);
                assertFalse(reason.isBlank(),
                        "推荐理由不应为空: " + c + " + " + d);
            }
        }
    }
}
