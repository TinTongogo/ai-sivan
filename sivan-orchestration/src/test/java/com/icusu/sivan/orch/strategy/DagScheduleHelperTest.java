package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DagScheduleHelperTest {

    @Test
    void isReady_dependsOn为null时返回true() {
        PhaseNode phase = PhaseNode.builder().phase(0).build();
        assertTrue(DagScheduleHelper.isReady(phase, contextWith()));
    }

    @Test
    void isReady_dependsOn为空时返回true() {
        PhaseNode phase = PhaseNode.builder().phase(1).dependsOn(List.of()).build();
        assertTrue(DagScheduleHelper.isReady(phase, contextWith()));
    }

    @Test
    void isReady_依赖已完成返回true() {
        PhaseNode phase = PhaseNode.builder().phase(2).dependsOn(List.of(0, 1)).build();
        ContextPackage ctx = contextWith();
        ctx = ctx.withPhaseOutput(0, new PhaseOutput("done"));
        ctx = ctx.withPhaseOutput(1, new PhaseOutput("done"));
        assertTrue(DagScheduleHelper.isReady(phase, ctx));
    }

    @Test
    void isReady_依赖未完成返回false() {
        PhaseNode phase = PhaseNode.builder().phase(2).dependsOn(List.of(0, 1)).build();
        ContextPackage ctx = contextWith();
        ctx = ctx.withPhaseOutput(0, new PhaseOutput("done"));
        assertFalse(DagScheduleHelper.isReady(phase, ctx));
    }

    @Test
    void isReady_全部依赖缺失返回false() {
        PhaseNode phase = PhaseNode.builder().phase(2).dependsOn(List.of(0, 1)).build();
        assertFalse(DagScheduleHelper.isReady(phase, contextWith()));
    }

    @Test
    void hasExplicitDependencies_dependsOn为null返回false() {
        PhaseNode phase = PhaseNode.builder().phase(0).build();
        assertFalse(DagScheduleHelper.hasExplicitDependencies(phase));
    }

    @Test
    void hasExplicitDependencies_dependsOn存在返回true() {
        PhaseNode phase = PhaseNode.builder().phase(1).dependsOn(List.of(0)).build();
        assertTrue(DagScheduleHelper.hasExplicitDependencies(phase));
    }

    private static ContextPackage contextWith() {
        return new ContextPackage("input");
    }
}
