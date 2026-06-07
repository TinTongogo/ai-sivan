package com.icusu.sivan.domain.task;

import com.icusu.sivan.common.enums.SquadMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionModeRecommendationTest {

    @Test
    void shouldCreateChatShape() {
        var rec = new ExecutionModeRecommendation(
                ExecutionShape.CHAT,
                null, null, "简单回复"
        );
        assertEquals(ExecutionShape.CHAT, rec.shape());
        assertNull(rec.squadMode());
        assertNull(rec.phaseMode());
    }

    @Test
    void shouldThrowWhenChatShapeHasMode() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionModeRecommendation(
                        ExecutionShape.CHAT,
                        SquadMode.SEQUENTIAL, null, "不应该有模式"));
    }

    @Test
    void shouldCreateSquadShape() {
        var rec = new ExecutionModeRecommendation(
                ExecutionShape.SQUAD,
                SquadMode.HIERARCHICAL, SquadMode.SEQUENTIAL, "复杂任务需编排"
        );
        assertEquals(ExecutionShape.SQUAD, rec.shape());
        assertEquals(SquadMode.HIERARCHICAL, rec.squadMode());
    }

    @Test
    void shouldThrowWhenSquadShapeHasNoMode() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionModeRecommendation(
                        ExecutionShape.SQUAD,
                        null, null, "缺少模式"));
    }

    @Test
    void shouldThrowWhenReasonIsEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionModeRecommendation(
                        ExecutionShape.CHAT,
                        null, null, ""));
    }

    @Test
    void shouldCreateSingleAgentShape() {
        var rec = new ExecutionModeRecommendation(
                ExecutionShape.SINGLE_AGENT,
                null, null, "单一任务无需编排"
        );
        assertEquals(ExecutionShape.SINGLE_AGENT, rec.shape());
        assertNull(rec.squadMode());
        assertNull(rec.phaseMode());
    }
}
