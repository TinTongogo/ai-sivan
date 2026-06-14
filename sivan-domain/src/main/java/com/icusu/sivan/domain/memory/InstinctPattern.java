package com.icusu.sivan.domain.memory;

import com.icusu.sivan.domain.memory.PatternFeatureVector;
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
    /** 成功率（滑动窗口，最近 N 次）。 */
    private Double successRate;
    /** 模板权重（0~1），影响匹配优先级。 */
    private Double weight;

    // ===== 生命周期 =====

    /** 是否为草稿（true=探索阶段，false=已激活）。 */
    private Boolean draft;

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
        next.setDraft(false);
        next.setWeight(this.weight != null ? this.weight * 0.8 : 0.5);
        next.setSuccessRate(0.0);
        return next;
    }

    /** 记录一次命中（模板被匹配时调用）。 */
    public void recordHit() {
        this.hitCount = (this.hitCount != null ? this.hitCount : 0) + 1;
        this.totalCount = (this.totalCount != null ? this.totalCount : 0) + 1;
        this.lastMatchAt = LocalDateTime.now();
    }

    /** 记录一次执行结果（带滑动窗口成功率计算，窗口大小=20）。 */
    public void recordOutcome(boolean success) {
        this.totalCount = (this.totalCount != null ? this.totalCount : 0) + 1;
        if (success) {
            this.successCount = (this.successCount != null ? this.successCount : 0) + 1;
        }
        // 滑动窗口成功率：最近 WINDOW_SIZE 次
        int n = this.totalCount;
        int window = Math.min(n, 20);
        double prevRate = this.successRate != null ? this.successRate : 0.0;
        // 递推计算：新成功率 = (旧成功率 * (窗口-1) + 本次结果) / 窗口
        this.successRate = (prevRate * (window - 1) + (success ? 1.0 : 0.0)) / window;
        // 权重随成功率动态调整
        this.weight = this.successRate * 0.8 + 0.2;
        if (this.weight > 1.0) this.weight = 1.0;
    }

    /** 计算综合匹配优先级分数。 */
    public double priorityScore() {
        double w = weight != null ? weight : 0.5;
        double sr = successRate != null ? successRate : 0.0;
        return w * sr;
    }
}
