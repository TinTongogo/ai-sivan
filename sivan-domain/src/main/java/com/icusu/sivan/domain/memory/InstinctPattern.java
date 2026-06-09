package com.icusu.sivan.domain.memory;

import com.icusu.sivan.domain.task.PatternFeatureVector;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本能模板实体。
 * 特征驱动的任务执行路径元模板。
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
    /** 执行形态（Forest 模式：SEQUENTIAL / PARALLEL / CONDITIONAL / HIERARCHICAL / CONSENSUS）。 */
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
}
