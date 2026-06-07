package com.icusu.sivan.orch.hitl;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.orchestration.HitlReview;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/**
 * HITL 审核服务测试。
 */
class HitlServiceTest {

    @Mock
    private IHitlReviewRepository hitlReviewRepository;
    @Mock
    private ISquadExecutionRepository squadExecutionRepository;
    @Mock
    private ISquadRepository squadRepository;
    @Mock
    private IPatternFeedbackRepository patternFeedbackRepository;

    @Captor
    private ArgumentCaptor<PatternFeedbackRecord> feedbackCaptor;

    private HitlService hitlService;
    private final UUID accountId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID squadId = UUID.randomUUID();
    private final UUID patternId = UUID.randomUUID();

    /**
     * 初始化测试环境。
     */
    @BeforeEach
    void setUp() {
        hitlService = new HitlService(hitlReviewRepository, squadExecutionRepository,
                squadRepository, patternFeedbackRepository);
    }

    /**
     * 创建审核应保存记录并更新执行状态。
     */
    @Test
    void createReview_shouldSaveAndUpdateStatus() {
        HitlReview result = hitlService.createReview(accountId, executionId, 2, "代码审查", "input", "output");

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        assertEquals(2, result.getPhase());
        assertEquals("代码审查", result.getPhaseName());
        verify(hitlReviewRepository).save(any(HitlReview.class));
        verify(squadExecutionRepository).updateStatus(eq(executionId), eq("HITL_PENDING"), any());
    }

    /**
     * 创建审核应处理空阶段名称。
     */
    @Test
    void createReview_shouldHandleNullPhaseName() {
        HitlReview result = hitlService.createReview(accountId, executionId, 0, null, "input", null);

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        verify(squadExecutionRepository).updateStatus(eq(executionId), eq("HITL_PENDING"), any());
    }

    /**
     * 通过审核应更新状态和反馈。
     */
    @Test
    void approve_shouldSetApprovedAndUpdateStatus() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        HitlReview result = hitlService.approve(reviewId, "看起来没问题");

        assertEquals("APPROVED", review.getStatus());
        assertEquals("看起来没问题", review.getHumanFeedback());
        assertSame(review, result);
        verify(hitlReviewRepository).update(review);
        verify(squadExecutionRepository).updateStatus(executionId, "RUNNING", "人工审核通过");
        // approve 不写入反馈记录
        verify(patternFeedbackRepository, never()).save(any());
    }

    /**
     * 审核不存在时应抛出异常。
     */
    @Test
    void approve_shouldThrow_whenNotFound() {
        UUID reviewId = UUID.randomUUID();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> hitlService.approve(reviewId, "ok"));
    }

    /**
     * 驳回审核应更新状态和反馈。
     */
    @Test
    void reject_shouldSetRejectedAndUpdateStatus() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        HitlReview result = hitlService.reject(reviewId, "需要重新设计");

        assertEquals("REJECTED", review.getStatus());
        assertEquals("需要重新设计", review.getHumanFeedback());
        assertSame(review, result);
        verify(squadExecutionRepository).updateStatus(executionId, "FAILED", "人工审核驳回: 需要重新设计");
    }

    /**
     * 驳回不存在审核时应抛出异常。
     */
    @Test
    void reject_shouldThrow_whenNotFound() {
        UUID reviewId = UUID.randomUUID();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> hitlService.reject(reviewId, "驳回"));
    }

    /**
     * 修正审核应注入修正内容并继续执行。
     */
    @Test
    void correct_shouldSetCorrectedAndUpdateStatus() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        HitlReview result = hitlService.correct(reviewId, "修正后的代码内容");

        assertEquals("CORRECTED", review.getStatus());
        assertEquals("修正后的代码内容", review.getCorrectedContent());
        assertSame(review, result);
        verify(hitlReviewRepository).update(review);
        verify(squadExecutionRepository).updateStatus(executionId, "RUNNING", "人工修正: 注入修正内容");
        // CORRECT 写入反馈记录
        verify(patternFeedbackRepository).save(any());
    }

    /**
     * 修正不存在的审核应抛出异常。
     */
    @Test
    void correct_shouldThrow_whenNotFound() {
        UUID reviewId = UUID.randomUUID();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> hitlService.correct(reviewId, "修正内容"));
    }

    /**
     * 非待审核状态下修正应抛出冲突异常。
     */
    @Test
    void correct_shouldThrow_whenNotPending() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId).status("APPROVED").build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertThrows(DomainException.class, () -> hitlService.correct(reviewId, "修正内容"));
    }

    /**
     * CORRECT 反馈记录包含从 Squad 反查的 patternId。
     */
    @Test
    void correct_shouldWriteFeedbackWithPatternId() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .accountId(accountId)
                .status("PENDING")
                .build();
        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId)
                .squadId(squadId)
                .build();
        Squad squad = Squad.builder()
                .squadId(squadId)
                .sourcePatternId(patternId)
                .build();

        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(squadExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        hitlService.correct(reviewId, "修正内容");

        verify(patternFeedbackRepository).save(feedbackCaptor.capture());
        PatternFeedbackRecord record = feedbackCaptor.getValue();
        assertEquals(patternId, record.getPatternId());
        assertEquals(accountId, record.getAccountId());
        assertEquals(executionId, record.getExecutionId());
        assertEquals(PatternFeedbackRecord.FeedbackOutcome.PARTIAL, record.getOutcome());
        assertEquals("TRIGGER_MANUAL", record.getSource());
    }

    /**
     * CORRECT 反馈记录在 Squad 无 patternId 时仍可写入（patternId 为 null）。
     */
    @Test
    void correct_shouldWriteFeedback_whenPatternIdNotResolved() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        // 模拟 Squad 无 sourcePatternId（未匹配到模板）
        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId)
                .squadId(squadId)
                .build();
        Squad squad = Squad.builder()
                .squadId(squadId)
                .sourcePatternId(null)
                .build();

        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(squadExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        hitlService.correct(reviewId, "修正内容");

        verify(patternFeedbackRepository).save(feedbackCaptor.capture());
        assertNull(feedbackCaptor.getValue().getPatternId());
    }

    /**
     * CORRECT 反馈记录写入异常不应传播（不影响 HITL 流程）。
     */
    @Test
    void correct_shouldNotThrow_whenFeedbackSaveFails() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        // 反查或保存异常被 catch 吞掉
        when(squadExecutionRepository.findById(executionId))
                .thenThrow(new RuntimeException("DB 异常"));

        assertDoesNotThrow(() -> hitlService.correct(reviewId, "修正内容"));
        // HITL 主流程不受影响
        verify(hitlReviewRepository).update(review);
        verify(squadExecutionRepository).updateStatus(any(), any(), any());
    }

    /**
     * RESTART 反馈记录包含从 Squad 反查的 patternId。
     */
    @Test
    void restartPhase_shouldWriteFeedbackWithPatternId() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .accountId(accountId)
                .status("PENDING")
                .build();
        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId)
                .squadId(squadId)
                .build();
        Squad squad = Squad.builder()
                .squadId(squadId)
                .sourcePatternId(patternId)
                .build();

        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(squadExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(squadRepository.findById(squadId)).thenReturn(Optional.of(squad));

        hitlService.restartPhase(reviewId, "请改进错误处理");

        verify(patternFeedbackRepository).save(feedbackCaptor.capture());
        PatternFeedbackRecord record = feedbackCaptor.getValue();
        assertEquals(patternId, record.getPatternId());
        assertEquals(PatternFeedbackRecord.FeedbackOutcome.PARTIAL, record.getOutcome());
        assertEquals("TRIGGER_MANUAL", record.getSource());
    }

    /**
     * restartAgent 不写入反馈记录（仅 CORRECT/RESTART_PHASE 触发）。
     */
    @Test
    void restartAgent_shouldNotWriteFeedback() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        hitlService.restartAgent(reviewId, "高级编码员", "重构模块");

        verify(hitlReviewRepository).update(review);
        verify(patternFeedbackRepository, never()).save(any());
    }

    /**
     * 重启阶段应设置 RESTART_PHASE 并更新执行状态。
     */
    @Test
    void restartPhase_shouldSetRestartPhaseAndUpdateStatus() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        HitlReview result = hitlService.restartPhase(reviewId, "请增加错误处理");

        assertEquals("RESTART_PHASE", review.getStatus());
        assertEquals("请增加错误处理", review.getRestartHint());
        assertSame(review, result);
        verify(squadExecutionRepository).updateStatus(executionId, "RUNNING",
                "重启阶段: 请增加错误处理");
        // RESTART_PHASE 写入反馈记录
        verify(patternFeedbackRepository).save(any());
    }

    /**
     * 重启不存在的审核应抛出异常。
     */
    @Test
    void restartPhase_shouldThrow_whenNotFound() {
        UUID reviewId = UUID.randomUUID();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> hitlService.restartPhase(reviewId, "hint"));
    }

    /**
     * 非待审核状态下重启阶段应抛出冲突异常。
     */
    @Test
    void restartPhase_shouldThrow_whenNotPending() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId).status("TIMEOUT").build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertThrows(DomainException.class, () -> hitlService.restartPhase(reviewId, "hint"));
    }

    /**
     * 重启智能体应设置 RESTART_AGENT 并更新执行状态。
     */
    @Test
    void restartAgent_shouldSetRestartAgentAndUpdateStatus() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId)
                .executionId(executionId)
                .status("PENDING")
                .build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        HitlReview result = hitlService.restartAgent(reviewId, "高级编码员", "请重构此模块");

        assertEquals("RESTART_AGENT", review.getStatus());
        assertEquals("高级编码员", review.getRestartAgent());
        assertEquals("请重构此模块", review.getRestartHint());
        assertSame(review, result);
        verify(squadExecutionRepository).updateStatus(executionId, "RUNNING",
                "重启智能体: 高级编码员 - 请重构此模块");
    }

    /**
     * 重启不存在的智能体审核应抛出异常。
     */
    @Test
    void restartAgent_shouldThrow_whenNotFound() {
        UUID reviewId = UUID.randomUUID();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> hitlService.restartAgent(reviewId, "agent", "hint"));
    }

    /**
     * 非待审核状态下重启智能体应抛出冲突异常。
     */
    @Test
    void restartAgent_shouldThrow_whenNotPending() {
        UUID reviewId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .reviewId(reviewId).status("REJECTED").build();
        when(hitlReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertThrows(DomainException.class, () -> hitlService.restartAgent(reviewId, "agent", "hint"));
    }

    /**
     * 按执行 ID 查询审核应委托仓库。
     */
    @Test
    void getReviewsByExecution_shouldDelegate() {
        List<HitlReview> expected = List.of(
                HitlReview.builder().reviewId(UUID.randomUUID()).build()
        );
        when(hitlReviewRepository.findByExecutionId(executionId)).thenReturn(expected);

        List<HitlReview> result = hitlService.getReviewsByExecution(executionId);

        assertEquals(1, result.size());
        verify(hitlReviewRepository).findByExecutionId(executionId);
    }
}
