package com.icusu.sivan.orch.topology;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructuralSimilarity 单元测试。
 */
class StructuralSimilarityTest {

    @Test
    void identicalPhases_shouldReturnHighSimilarity() {
        var phases = List.of(
                phase(0, "分析", List.of("分析员")),
                phase(1, "编码", List.of("程序员"))
        );
        double sim = StructuralSimilarity.compute(phases, phases);
        assertTrue(sim >= 0.95, "相同列表应返回接近 1.0，实际=" + sim);
    }

    @Test
    void completelyDifferent_shouldReturnLowSimilarity() {
        var template = List.of(
                phase(0, "A", List.of("agent1")),
                phase(1, "B", List.of("agent2"))
        );
        var generated = List.of(
                phase(0, "X", List.of("other1")),
                phase(1, "Y", List.of("other2")),
                phase(2, "Z", List.of("other3"))
        );
        double sim = StructuralSimilarity.compute(template, generated);
        assertTrue(sim < 0.5, "完全不同应低于 0.5，实际=" + sim);
    }

    @Test
    void partiallySimilar_shouldReturnModerateScore() {
        var template = List.of(
                phase(0, "分析", List.of("分析员", "研究员")),
                phase(1, "编码", List.of("程序员")),
                phase(2, "测试", List.of("测试员"))
        );
        var generated = List.of(
                phase(0, "分析", List.of("分析员")),
                phase(1, "实现", List.of("程序员", "架构师")),
                phase(2, "验证", List.of("QA"))
        );
        double sim = StructuralSimilarity.compute(template, generated);
        // Same count (3 phases), Jaccard = 2/6 ≈ 0.333
        // 0.3 * 1.0 + 0.7 * 0.333 = 0.533
        assertTrue(sim > 0.4 && sim < 0.7, "部分相似应在 0.4~0.7，实际=" + sim);
    }

    @Test
    void emptyLists_shouldReturnOne() {
        assertEquals(1.0, StructuralSimilarity.compute(List.of(), List.of()));
    }

    @Test
    void oneEmptyList_shouldReturnZero() {
        var phases = List.of(phase(0, "A", List.of("a1")));
        assertEquals(0.0, StructuralSimilarity.compute(phases, List.of()));
        assertEquals(0.0, StructuralSimilarity.compute(List.of(), phases));
    }

    @Test
    void nullInput_shouldReturnZero() {
        var phases = List.of(phase(0, "A", List.of("a1")));
        assertEquals(0.0, StructuralSimilarity.compute(null, phases));
        assertEquals(0.0, StructuralSimilarity.compute(phases, null));
        assertEquals(0.0, StructuralSimilarity.compute(null, null));
    }

    @Test
    void sameCountDifferentNames_shouldReflectAgentOverlap() {
        var template = List.of(
                phase(0, "A", List.of("通用智能体")),
                phase(1, "B", List.of("通用智能体"))
        );
        var generated = List.of(
                phase(0, "A", List.of("通用智能体", "专家"))
        );
        // count sim = 1/2 = 0.5, Jaccard = 1/2 = 0.5
        // 0.3 * 0.5 + 0.7 * 0.5 = 0.5
        double sim = StructuralSimilarity.compute(template, generated);
        assertEquals(0.5, sim, 0.01);
    }

    @Test
    void jaccardIdentical_shouldReturnOne() {
        assertEquals(1.0, StructuralSimilarity.jaccardSimilarity(
                Set.of("a", "b"), Set.of("a", "b")));
    }

    @Test
    void jaccardDisjoint_shouldReturnZero() {
        assertEquals(0.0, StructuralSimilarity.jaccardSimilarity(
                Set.of("a", "b"), Set.of("c", "d")));
    }

    @Test
    void jaccardBothEmpty_shouldReturnOne() {
        assertEquals(1.0, StructuralSimilarity.jaccardSimilarity(Set.of(), Set.of()));
    }

    private static PhaseNode phase(int index, String name, List<String> agents) {
        return PhaseNode.builder()
                .phase(index)
                .name(name)
                .agents(agents)
                .mode(SquadMode.SEQUENTIAL)
                .build();
    }
}
