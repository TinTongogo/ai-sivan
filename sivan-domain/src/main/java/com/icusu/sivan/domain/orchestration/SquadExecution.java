package com.icusu.sivan.domain.orchestration;

import com.icusu.sivan.common.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Squad 执行记录聚合根。
 * <p>状态流转：RUNNING → COMPLETED / FAILED / HITL_PENDING；HITL_PENDING → RUNNING / COMPLETED。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SquadExecution {

    private UUID executionId;
    private UUID squadId;
    private UUID accountId;
    private UUID projectId;
    private String taskDescription;
    private ExecutionStatus status;
    private String topologySnapshot;
    private Map<String, Object> context;
    private String agentState;
    private Integer currentPhase;
    private String content;
    private String thinking;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime pausedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    // ===== 领域行为：状态流转 =====

    /** 开始执行。 */
    public void start() {
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    /** 执行完成。 */
    public void complete() {
        if (this.status != ExecutionStatus.RUNNING && this.status != ExecutionStatus.HITL_PENDING) {
            throw new IllegalStateException("仅 RUNNING 或 HITL_PENDING 状态可完成，当前: " + this.status);
        }
        this.status = ExecutionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /** 执行失败。 */
    public void fail(String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /** 暂停等待人工审核。 */
    public void pauseForHitl(int phase, String reason) {
        if (this.status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("仅 RUNNING 状态可暂停，当前: " + this.status);
        }
        this.status = ExecutionStatus.HITL_PENDING;
        this.currentPhase = phase;
        this.pausedAt = LocalDateTime.now();
    }

    /** HITL 审核通过后恢复执行。 */
    public void resumeFromHitl() {
        if (this.status != ExecutionStatus.HITL_PENDING) {
            throw new IllegalStateException("仅 HITL_PENDING 状态可恢复，当前: " + this.status);
        }
        this.status = ExecutionStatus.RUNNING;
        this.pausedAt = null;
    }

    /** 判断是否正在运行中。 */
    public boolean isRunning() {
        return this.status == ExecutionStatus.RUNNING;
    }

    /** 判断是否已完成（成功或失败）。 */
    public boolean isTerminal() {
        return this.status == ExecutionStatus.COMPLETED || this.status == ExecutionStatus.FAILED;
    }
}
