package com.icusu.sivan.domain.memory;

import com.icusu.sivan.domain.task.ExecutionPath;
import com.icusu.sivan.domain.task.ExecutionModeRecommendation;
import com.icusu.sivan.domain.task.ExecutionShape;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本能模板实体。
 * 特征驱动的任务执行路径元模板，原生支持 CHAT / SINGLE_AGENT / SQUAD 三种执行形态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstinctPattern {

    private UUID patternId;
    private UUID accountId;

    /** 阶段拓扑 JSON（Squad 的执行阶段结构）。 */
    private String topologyJson;

    /** 特征概率分布向量。 */
    private PatternFeatureVector featureVector;
    /** 执行形态：CHAT / SINGLE_AGENT / SQUAD。 */
    private String executionMode;

    // ===== 统计 =====

    private Integer hitCount;
    private Integer successCount;
    private Integer totalCount;

    // ===== 版本与追溯 =====

    /** 来源模板 ID（版本追溯用，首次创建为 null）。 */
    private UUID sourcePatternId;
    /** 模板版本号，从 1 开始递增。 */
    private Integer version;

    // ===== 模式推荐（缓存） =====

    /** 推荐执行模式组合（命中时直接使用，跳过 LLM 选择）。 */
    private ExecutionModeRecommendation modeRecommendation;
    /** 此模式选择的历史成功率。 */
    private Double modeSuccessRate;

    // ===== 状态 =====

    private Boolean active;
    private LocalDateTime lastMatchAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 领域行为 =====

    /** 创建新版本模板（基于当前模板的演进）。 */
    public InstinctPattern newVersion() {
        InstinctPattern next = new InstinctPattern();
        next.setAccountId(this.accountId);
        next.setFeatureVector(this.featureVector);
        next.setExecutionMode(this.executionMode);
        next.setSourcePatternId(this.patternId);
        next.setVersion(this.version != null ? this.version + 1 : 2);
        next.setActive(true);
        return next;
    }

    /** 记录一次命中。 */
    public void recordHit() {
        this.hitCount = (this.hitCount != null ? this.hitCount : 0) + 1;
        this.totalCount = (this.totalCount != null ? this.totalCount : 0) + 1;
        this.lastMatchAt = LocalDateTime.now();
    }

    /** 记录一次执行结果。 */
    public void recordOutcome(boolean success) {
        this.totalCount = (this.totalCount != null ? this.totalCount : 0) + 1;
        if (success) {
            this.successCount = (this.successCount != null ? this.successCount : 0) + 1;
        }
    }

    /** 转换为执行路径。 */
    public ExecutionPath toExecutionPath() {
        ExecutionShape shape = parseExecutionMode(this.executionMode);
        String reason = this.modeRecommendation != null
                ? this.modeRecommendation.reason()
                : "本能模板匹配 (patternId=" + this.patternId + ")";
        return new ExecutionPath(shape, null, null, this.topologyJson, reason);
    }

    private static ExecutionShape parseExecutionMode(String mode) {
        if (mode == null) return ExecutionShape.SQUAD;
        return switch (mode.toUpperCase()) {
            case "CHAT" -> ExecutionShape.CHAT;
            case "SINGLE_AGENT" -> ExecutionShape.SINGLE_AGENT;
            default -> ExecutionShape.SQUAD;
        };
    }
}
