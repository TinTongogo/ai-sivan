package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HitlDecisionTest {

    @Test
    void shouldCreateApprove() {
        var d = HitlDecision.approve();
        assertEquals(HitlDecision.Action.APPROVE, d.action());
        assertNull(d.correctedContent());
    }

    @Test
    void shouldCreateReject() {
        var d = HitlDecision.reject();
        assertEquals(HitlDecision.Action.REJECT, d.action());
    }

    @Test
    void shouldCreateCorrect() {
        var d = HitlDecision.correct("修正内容");
        assertEquals(HitlDecision.Action.CORRECT, d.action());
        assertEquals("修正内容", d.correctedContent());
    }

    @Test
    void shouldThrowWhenCorrectContentMissing() {
        assertThrows(IllegalArgumentException.class, () -> HitlDecision.correct(""));
    }

    @Test
    void shouldCreateRestartPhase() {
        var d = HitlDecision.restartPhase("提示");
        assertEquals(HitlDecision.Action.RESTART_PHASE, d.action());
        assertEquals("提示", d.restartHint());
    }

    @Test
    void shouldThrowWhenRestartPhaseHintMissing() {
        assertThrows(IllegalArgumentException.class, () -> HitlDecision.restartPhase(""));
    }

    @Test
    void shouldCreateRestartAgent() {
        var d = HitlDecision.restartAgent("agent1", "提示");
        assertEquals(HitlDecision.Action.RESTART_AGENT, d.action());
        assertEquals("agent1", d.restartAgent());
        assertEquals("提示", d.restartHint());
    }

    @Test
    void shouldThrowWhenRestartAgentNameMissing() {
        assertThrows(IllegalArgumentException.class, () -> HitlDecision.restartAgent("", "提示"));
    }
}
