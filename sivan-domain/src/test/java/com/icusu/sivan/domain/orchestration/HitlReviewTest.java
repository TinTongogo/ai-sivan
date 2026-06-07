package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HitlReviewTest {

    @Test
    void approve_shouldSetStatusAndFeedback() {
        HitlReview r = review("PENDING");
        r.approve("ok");
        assertEquals("APPROVED", r.getStatus());
        assertEquals("ok", r.getHumanFeedback());
        assertNotNull(r.getUpdatedAt());
    }

    @Test
    void reject_shouldSetStatusAndFeedback() {
        HitlReview r = review("PENDING");
        r.reject("不行");
        assertEquals("REJECTED", r.getStatus());
        assertEquals("不行", r.getHumanFeedback());
    }

    @Test
    void timeout_shouldSetStatus() {
        HitlReview r = review("PENDING");
        r.timeout();
        assertEquals("TIMEOUT", r.getStatus());
    }

    @Test
    void correct_shouldSetCorrectedContent() {
        HitlReview r = review("PENDING");
        r.correct("修正后的内容");
        assertEquals("CORRECTED", r.getStatus());
        assertEquals("修正后的内容", r.getCorrectedContent());
    }

    @Test
    void restartPhase_shouldSetHint() {
        HitlReview r = review("PENDING");
        r.restartPhase("请改进错误处理");
        assertEquals("RESTART_PHASE", r.getStatus());
        assertEquals("请改进错误处理", r.getRestartHint());
    }

    @Test
    void restartAgent_shouldSetAgentAndHint() {
        HitlReview r = review("PENDING");
        r.restartAgent("编码员", "请重构此模块");
        assertEquals("RESTART_AGENT", r.getStatus());
        assertEquals("编码员", r.getRestartAgent());
        assertEquals("请重构此模块", r.getRestartHint());
    }

    @Test
    void isExpired_shouldReturnTrue_whenPastExpiry() {
        HitlReview r = review("PENDING");
        r.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertTrue(r.isExpired());
    }

    @Test
    void isExpired_shouldReturnFalse_whenNotExpired() {
        HitlReview r = review("PENDING");
        r.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        assertFalse(r.isExpired());
    }

    @Test
    void isExpired_shouldReturnFalse_whenExpiresAtNull() {
        HitlReview r = review("PENDING");
        r.setExpiresAt(null);
        assertFalse(r.isExpired());
    }

    @Test
    void isPending_shouldReturnTrue() {
        HitlReview r = review("PENDING");
        assertTrue(r.isPending());
    }

    @Test
    void isPending_shouldReturnFalse_whenNotPending() {
        HitlReview r = review("APPROVED");
        assertFalse(r.isPending());
    }

    @Test
    void isCorrected_shouldReturnTrue() {
        HitlReview r = review("CORRECTED");
        assertTrue(r.isCorrected());
    }

    @Test
    void isRestartPhase_shouldReturnTrue() {
        HitlReview r = review("RESTART_PHASE");
        assertTrue(r.isRestartPhase());
    }

    @Test
    void isRestartAgent_shouldReturnTrue() {
        HitlReview r = review("RESTART_AGENT");
        assertTrue(r.isRestartAgent());
    }

    @Test
    void doubleMutation_shouldOverwriteState() {
        HitlReview r = review("PENDING");
        r.approve("ok");
        assertEquals("APPROVED", r.getStatus());
        r.reject("actually no");
        assertEquals("REJECTED", r.getStatus());
    }

    private static HitlReview review(String status) {
        return HitlReview.builder()
                .reviewId(UUID.randomUUID())
                .executionId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .phase(0).phaseName("审查")
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
