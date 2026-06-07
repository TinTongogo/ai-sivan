package com.icusu.sivan.domain.orchestration;

import com.icusu.sivan.common.enums.SquadMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SquadTest {

    @Test
    void isActive_shouldReturnTrue_whenActive() {
        Squad s = Squad.builder().active(true).build();
        assertTrue(s.isActive());
    }

    @Test
    void isActive_shouldReturnFalse_whenNotActive() {
        Squad s = Squad.builder().active(false).build();
        assertFalse(s.isActive());
    }

    @Test
    void isActive_shouldReturnFalse_whenNull() {
        Squad s = Squad.builder().active(null).build();
        assertFalse(s.isActive());
    }

    @Test
    void activate_shouldSetActiveAndTimestamp() {
        Squad s = Squad.builder().active(false).build();
        s.activate();
        assertTrue(s.isActive());
        assertNotNull(s.getUpdatedAt());
    }

    @Test
    void deactivate_shouldSetInactiveAndTimestamp() {
        Squad s = Squad.builder().active(true).build();
        s.deactivate();
        assertFalse(s.isActive());
        assertNotNull(s.getUpdatedAt());
    }

    @Test
    void recordUsage_shouldIncrementAndSetTimestamp() {
        Squad s = Squad.builder().usageCount(5).build();
        s.recordUsage();
        assertEquals(6, s.getUsageCount());
        assertNotNull(s.getLastUsedAt());
    }

    @Test
    void recordUsage_shouldHandleNullUsageCount() {
        Squad s = Squad.builder().usageCount(null).build();
        s.recordUsage();
        assertEquals(1, s.getUsageCount());
    }

    @Test
    void recordExecutionOutcome_shouldUpdateSuccessRate() {
        Squad s = Squad.builder().usageCount(3).successRate(0.5).build();
        s.recordExecutionOutcome(true);
        // (0.5 * 2 + 1.0) / 3 = 0.666...
        assertEquals(2.0 / 3, s.getSuccessRate(), 0.001);
    }

    @Test
    void recordExecutionOutcome_shouldHandleNullSuccessRate() {
        Squad s = Squad.builder().usageCount(2).successRate(null).build();
        s.recordExecutionOutcome(true);
        // (0.0 * 1 + 1.0) / 2 = 0.5
        assertEquals(0.5, s.getSuccessRate(), 0.001);
    }

    @Test
    void matchesTaskByName_shouldReturnTrue_whenNameContained() {
        Squad s = Squad.builder().name("代码审查").build();
        assertTrue(s.matchesTaskByName("请帮我做代码审查工作"));
    }

    @Test
    void matchesTaskByName_shouldReturnFalse_whenNameNotContained() {
        Squad s = Squad.builder().name("代码审查").build();
        assertFalse(s.matchesTaskByName("请帮我写单元测试"));
    }

    @Test
    void matchesTaskByName_shouldReturnFalse_whenTaskNull() {
        Squad s = Squad.builder().name("test").build();
        assertFalse(s.matchesTaskByName(null));
    }

    @Test
    void matchesTaskByKeywords_shouldReturnTrue_whenAboveThreshold() {
        Squad s = Squad.builder().description("Java, 后端, API, 数据库, 缓存").build();
        assertTrue(s.matchesTaskByKeywords("Java 后端 API 开发"));
    }

    @Test
    void matchesTaskByKeywords_shouldReturnFalse_whenBelowThreshold() {
        Squad s = Squad.builder().description("前端, React, UI, 组件, 样式").build();
        assertFalse(s.matchesTaskByKeywords("Java 后端 API"));
    }

    @Test
    void matchesTaskByKeywords_shouldReturnFalse_whenDescriptionNull() {
        Squad s = Squad.builder().description(null).build();
        assertFalse(s.matchesTaskByKeywords("task"));
    }

    @Test
    void addPhase_shouldInsertAndRenumber() {
        Squad s = Squad.builder().phases(new java.util.ArrayList<>()).build();
        s.addPhase(0, PhaseNode.builder().phase(99).name("A").agents(List.of("a")).build());
        s.addPhase(1, PhaseNode.builder().phase(99).name("B").agents(List.of("b")).build());
        assertEquals(0, s.getPhases().get(0).getPhase());
        assertEquals(1, s.getPhases().get(1).getPhase());
    }

    @Test
    void addPhase_shouldInitializeNullPhasesList() {
        Squad s = Squad.builder().phases(null).build();
        s.addPhase(0, PhaseNode.builder().phase(0).name("A").agents(List.of("a")).build());
        assertNotNull(s.getPhases());
        assertEquals(1, s.getPhases().size());
    }

    @Test
    void removePhase_shouldRemoveAndRenumber() {
        List<PhaseNode> phases = new java.util.ArrayList<>();
        phases.add(PhaseNode.builder().phase(0).name("A").agents(List.of("a")).build());
        phases.add(PhaseNode.builder().phase(1).name("B").agents(List.of("b")).build());
        phases.add(PhaseNode.builder().phase(2).name("C").agents(List.of("c")).build());
        Squad s = Squad.builder().phases(phases).build();
        s.removePhase(1);
        assertEquals(2, s.getPhases().size());
        assertEquals("C", s.getPhases().get(1).getName());
        assertEquals(1, s.getPhases().get(1).getPhase());
    }

    @Test
    void removePhase_shouldDoNothing_whenInvalidIndex() {
        Squad s = Squad.builder().phases(new java.util.ArrayList<>()).build();
        s.removePhase(-1); // should not throw
        s.removePhase(5);  // should not throw
    }

    @Test
    void validateInvariants_shouldPass_whenPhasesPresent() {
        Squad s = Squad.builder().phases(List.of(PhaseNode.builder().phase(0).name("A").agents(List.of("a")).build())).build();
        assertDoesNotThrow(s::validateInvariants);
    }

    @Test
    void validateInvariants_shouldThrow_whenNoPhases() {
        Squad s = Squad.builder().phases(null).build();
        assertThrows(IllegalStateException.class, s::validateInvariants);
    }

    @Test
    void updateFrom_shouldUpdateNonNullFields() {
        Squad s = Squad.builder().name("old").description("old").mode(SquadMode.SEQUENTIAL).build();
        s.updateFrom("new", null, null, null, null, null);
        assertEquals("new", s.getName());
        assertEquals("old", s.getDescription()); // not updated
    }
}
