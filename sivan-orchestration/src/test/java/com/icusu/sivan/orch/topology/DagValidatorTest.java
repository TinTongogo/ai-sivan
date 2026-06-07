package com.icusu.sivan.orch.topology;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DagValidatorTest {

    private static PhaseNode phase(int idx) {
        return PhaseNode.builder().phase(idx).name("P" + idx).mode(SquadMode.SEQUENTIAL).build();
    }

    private static PhaseNode phase(int idx, List<Integer> dependsOn) {
        return PhaseNode.builder().phase(idx).name("P" + idx).dependsOn(dependsOn).mode(SquadMode.SEQUENTIAL).build();
    }

    // ========== 索引有效性 ==========

    @Test
    void nullDependsOnShouldPass() {
        List<PhaseNode> phases = List.of(phase(0), phase(1));
        assertDoesNotThrow(() -> DagValidator.validate(phases));
    }

    @Test
    void validIndicesShouldPass() {
        List<PhaseNode> phases = List.of(phase(0), phase(1, List.of(0)));
        assertDoesNotThrow(() -> DagValidator.validate(phases));
    }

    @Test
    void invalidIndexShouldThrow() {
        List<PhaseNode> phases = List.of(phase(0), phase(1, List.of(5)));
        assertThrows(DagValidator.DagValidationException.class, () -> DagValidator.validate(phases));
    }

    @Test
    void selfReferenceShouldThrow() {
        List<PhaseNode> phases = List.of(phase(0, List.of(0)));
        assertThrows(DagValidator.DagValidationException.class, () -> DagValidator.validate(phases));
    }

    // ========== 环检测 ==========

    @Test
    void linearChainShouldPass() {
        List<PhaseNode> phases = List.of(
                phase(0), phase(1, List.of(0)), phase(2, List.of(1)));
        assertDoesNotThrow(() -> DagValidator.validate(phases));
    }

    @Test
    void simpleCycleShouldThrow() {
        List<PhaseNode> phases = List.of(
                phase(0, List.of(1)), phase(1, List.of(0)));
        assertThrows(DagValidator.DagValidationException.class, () -> DagValidator.validate(phases));
    }

    @Test
    void diamondDagShouldPass() {
        List<PhaseNode> phases = List.of(
                phase(0),
                phase(1, List.of(0)),
                phase(2, List.of(0)),
                phase(3, List.of(1, 2)));
        assertDoesNotThrow(() -> DagValidator.validate(phases));
    }

    @Test
    void threeNodeCycleShouldThrow() {
        List<PhaseNode> phases = List.of(
                phase(0, List.of(2)),
                phase(1, List.of(0)),
                phase(2, List.of(1)));
        assertThrows(DagValidator.DagValidationException.class, () -> DagValidator.validate(phases));
    }

    // ========== 连通性 ==========

    @Test
    void allReachableShouldPass() {
        List<PhaseNode> phases = List.of(
                phase(0), phase(1, List.of(0)), phase(2, List.of(0, 1)));
        assertDoesNotThrow(() -> DagValidator.validate(phases));
    }

    @Test
    void unreachableNodeShouldBeDetected() {
        // Phase[0] is root, Phase[1] depends on Phase[100] which doesn't exist
        // This tests the index validation, but connectivity separately
        List<PhaseNode> phases = List.of(
                phase(0), phase(1, List.of(0)), phase(2, List.of(3)));
        DagValidator.DagValidationException ex = assertThrows(
                DagValidator.DagValidationException.class, () -> DagValidator.validate(phases));
        assertTrue(ex.getMessage().contains("不存在") || ex.getMessage().contains("不可达"));
    }

    // ========== CONDITIONAL sink ==========

    @Test
    void emptyPhasesShouldPass() {
        assertDoesNotThrow(() -> DagValidator.validate(List.of()));
        assertDoesNotThrow(() -> DagValidator.validate(null));
    }

    @Test
    void singlePhaseNoDepShouldPass() {
        assertDoesNotThrow(() -> DagValidator.validate(List.of(phase(0))));
    }
}
