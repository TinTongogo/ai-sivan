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
import com.icusu.sivan.orch.executor.SquadExecutionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL（Human-In-The-Loop）审核服务。
 * 管理 Squad 执行中的人工审核节点：创建审核请求、批准/驳回、超时自动继续。
 * CORRECT / RESTART 操作同步写入本能模板反馈记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlService {

    private final IHitlReviewRepository hitlReviewRepository;
    private final ISquadExecutionRepository squadExecutionRepository;
    private final ISquadRepository squadRepository;
    private final IPatternFeedbackRepository patternFeedbackRepository;

    /** 非构造器注入：SquadExecutionEngine（异步恢复），@Lazy 避免循环依赖。 */
    @Autowired @Lazy
    private SquadExecutionEngine squadExecutionEngine;

    /** 是否自动继续超时 HITL 审核。 */
    @Value("${sivan.orchestration.hitl.auto-resume:true}")
    private boolean autoResumeHitl;

    /** HITL 自动续接处理中标记，防重入。 */
    private final ConcurrentHashMap<UUID, Boolean> processingHitl = new ConcurrentHashMap<>();

    /**
     * 默认审核等待时长。
     */
    private static final long TIMEOUT_MINUTES = 5;

    /**
     * 前端心跳延长时长。
     */
    private static final long EXTEND_MINUTES = 3;

    /**
     * 创建 HITL 审核请求，暂停阶段执行。
     */
    public HitlReview createReview(UUID accountId, UUID executionId, int phase, String phaseName,
                                   String inputContent, String outputContent) {
        HitlReview review = HitlReview.builder()
                .executionId(executionId)
                .accountId(accountId)
                .phase(phase)
                .phaseName(phaseName)
                .inputContent(inputContent)
                .outputContent(outputContent)
                .status("PENDING")
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(TIMEOUT_MINUTES))
                .build();
        hitlReviewRepository.save(review);

        squadExecutionRepository.updateStatus(executionId, "HITL_PENDING",
                "等待人工审核: " + (phaseName != null ? phaseName : "阶段 " + phase));

        log.info("HITL 审核请求已创建: executionId={}, phase={}, reviewId={}, expiresAt={}",
                executionId, phase, review.getReviewId(), review.getExpiresAt());
        return review;
    }

    /**
     * 根据 ID 获取审核记录。
     */
    public HitlReview getReview(UUID reviewId) {
        return hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
    }

    /**
     * 批准审核，继续执行。
     */
    @Transactional
    public HitlReview approve(UUID reviewId, String feedback) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) throw DomainException.conflict("审核记录不是待审核状态");

        review.approve(feedback);
        hitlReviewRepository.update(review);

        squadExecutionRepository.updateStatus(review.getExecutionId(), "RUNNING",
                "人工审核通过");

        log.info("HITL 审核通过: reviewId={}, executionId={}", reviewId, review.getExecutionId());
        return review;
    }

    /**
     * 驳回审核，标记执行为失败。
     */
    @Transactional
    public HitlReview reject(UUID reviewId, String feedback) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) throw DomainException.conflict("审核记录不是待审核状态");

        review.reject(feedback);
        hitlReviewRepository.update(review);

        squadExecutionRepository.updateStatus(review.getExecutionId(), "FAILED",
                "人工审核驳回: " + feedback);

        log.info("HITL 审核驳回: reviewId={}, executionId={}", reviewId, review.getExecutionId());
        return review;
    }

    /**
     * 延长审核等待时间（前端心跳）。
     */
    @Transactional
    public HitlReview extendTimeout(UUID reviewId) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) return review;

        review.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(EXTEND_MINUTES));
        hitlReviewRepository.update(review);

        log.debug("HITL 审核等待时间已延长: reviewId={}, newExpiresAt={}", reviewId, review.getExpiresAt());
        return review;
    }

    /**
     * 查询指定执行的所有审核记录。
     */
    public List<HitlReview> getReviewsByExecution(UUID executionId) {
        return hitlReviewRepository.findByExecutionId(executionId);
    }

    /**
     * 查询指定执行当前的待审核记录。
     */
    public Optional<HitlReview> getCurrentReview(UUID executionId) {
        return hitlReviewRepository.findByExecutionId(executionId).stream()
                .filter(r -> "PENDING".equals(r.getStatus()))
                .findFirst();
    }

    /**
     * 修正审核结果，注入修正内容后继续执行。
     * 同步写入本能模板反馈记录（outcome=PARTIAL, source=TRIGGER_MANUAL）。
     */
    @Transactional
    public HitlReview correct(UUID reviewId, String correctedContent) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) throw DomainException.conflict("审核记录不是待审核状态");

        review.correct(correctedContent);
        hitlReviewRepository.update(review);

        squadExecutionRepository.updateStatus(review.getExecutionId(), "RUNNING",
                "人工修正: 注入修正内容");

        saveManualFeedback(review.getExecutionId(), review.getAccountId(),
                "人工修正: " + (correctedContent != null ? truncate(correctedContent, 80) : ""));

        log.info("HITL 审核修正: reviewId={}, executionId={}", reviewId, review.getExecutionId());
        return review;
    }

    /**
     * 重启阶段，使用修正提示重新执行当前阶段。
     * 同步写入本能模板反馈记录（outcome=PARTIAL, source=TRIGGER_MANUAL）。
     */
    @Transactional
    public HitlReview restartPhase(UUID reviewId, String hint) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) throw DomainException.conflict("审核记录不是待审核状态");

        review.restartPhase(hint);
        hitlReviewRepository.update(review);

        squadExecutionRepository.updateStatus(review.getExecutionId(), "RUNNING",
                "重启阶段: " + hint);

        saveManualFeedback(review.getExecutionId(), review.getAccountId(),
                "重启阶段: " + (hint != null ? truncate(hint, 80) : ""));

        log.info("HITL 重启阶段: reviewId={}, executionId={}, hint={}",
                reviewId, review.getExecutionId(), hint);
        return review;
    }

    /**
     * 重启智能体，更换 Agent 后重新执行。
     */
    @Transactional
    public HitlReview restartAgent(UUID reviewId, String agentName, String hint) {
        HitlReview review = hitlReviewRepository.findById(reviewId)
                .orElseThrow(() -> DomainException.notFound("HitlReview", reviewId));
        if (!review.isPending()) throw DomainException.conflict("审核记录不是待审核状态");

        review.restartAgent(agentName, hint);
        hitlReviewRepository.update(review);

        squadExecutionRepository.updateStatus(review.getExecutionId(), "RUNNING",
                "重启智能体: " + agentName + " - " + hint);

        log.info("HITL 重启智能体: reviewId={}, executionId={}, agent={}, hint={}",
                reviewId, review.getExecutionId(), agentName, hint);
        return review;
    }

    /**
     * 查找所有已过期的待审核记录。
     */
    public List<HitlReview> findExpiredReviews() {
        return hitlReviewRepository.findPendingExpired(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 定时恢复过期 HITL 审核（每 30 秒）。
     * 超时后根据配置自动继续（AUTO_APPROVE）或标记为放弃。
     * 统一 @Scheduled 入口，同步/异步路径共享。
     */
    @Scheduled(fixedRate = 30000)
    public void autoResumeExpired() {
        List<HitlReview> expired = hitlReviewRepository.findPendingExpired(LocalDateTime.now(ZoneOffset.UTC));
        for (HitlReview review : expired) {
            UUID reviewId = review.getReviewId();
            if (processingHitl.putIfAbsent(reviewId, Boolean.TRUE) != null) continue;
            try {
                SquadExecution execution = squadExecutionRepository.findById(review.getExecutionId()).orElse(null);
                if (execution == null) continue;

                if (!autoResumeHitl) {
                    review.setStatus("ABANDONED");
                    review.setHumanFeedback("审核超时，自动放弃");
                    review.setExpiresAt(null);
                    hitlReviewRepository.update(review);
                    squadExecutionRepository.updateStatus(review.getExecutionId(), "FAILED", "审核超时已放弃");
                    log.info("HITL 审核超时已放弃: executionId={}, reviewId={} (autoResume=false)",
                            review.getExecutionId(), reviewId);
                    continue;
                }

                review.setStatus("TIMEOUT");
                review.setHumanFeedback("审核超时，自动继续");
                review.setExpiresAt(null);
                hitlReviewRepository.update(review);

                // 尝试通过 SquadExecutionEngine 恢复（异步路径）
                try {
                    squadExecutionEngine.resume(execution, execution.getAccountId());
                } catch (Exception e) {
                    log.warn("HITL 异步恢复失败, executionId={}: {}", review.getExecutionId(), e.getMessage());
                    // 降级：至少将执行状态设为 RUNNING，让前端/下次触发可接续
                    squadExecutionRepository.updateStatus(review.getExecutionId(), "RUNNING", "审核超时自动继续(降级)");
                }

                log.info("HITL 审核超时自动继续: executionId={}, reviewId={}",
                        review.getExecutionId(), reviewId);
            } catch (Exception e) {
                log.error("HITL 超时自动继续失败: reviewId={}", reviewId, e);
            } finally {
                processingHitl.remove(reviewId);
            }
        }
    }

    // ========== 反馈联动 ==========

    /**
     * 保存人工修正触发的手动反馈记录。
     */
    private void saveManualFeedback(UUID executionId, UUID accountId, String reason) {
        try {
            // 尝试反查 patternId（执行 → squad → 拓扑模板）
            UUID patternId = resolvePatternId(executionId);

            PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                    .patternId(patternId)
                    .accountId(accountId)
                    .executionId(executionId)
                    .outcome(PatternFeedbackRecord.FeedbackOutcome.PARTIAL)
                    .outcomeReason(reason)
                    .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_MANUAL.name())
                    .build();
            patternFeedbackRepository.save(record);
            log.debug("手动反馈记录已保存: executionId={}, patternId={}", executionId, patternId);
        } catch (Exception e) {
            log.warn("保存手动反馈记录失败: executionId={}", executionId, e);
        }
    }

    /**
     * 从执行记录反向查找关联的模板 ID。
     * 执行记录 → Squad → Squad.sourcePatternId。
     */
    private UUID resolvePatternId(UUID executionId) {
        try {
            Optional<SquadExecution> execOpt = squadExecutionRepository.findById(executionId);
            return execOpt.map(SquadExecution::getSquadId).flatMap(squadId ->
                    squadRepository.findById(squadId)
            ).map(Squad::getSourcePatternId).orElse(null);
        } catch (Exception e) {
            log.warn("反查 patternId 失败: executionId={}", executionId, e);
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
