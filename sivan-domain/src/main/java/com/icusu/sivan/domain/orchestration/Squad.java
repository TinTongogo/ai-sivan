package com.icusu.sivan.domain.orchestration;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.SquadSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Squad 编排模板聚合根。
 * <p>不变量：phases 不为空，phase 序号唯一且连续。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Squad {

    private UUID squadId;
    private UUID accountId;
    private UUID projectId;
    private String name;
    private String description;
    private SquadMode mode;
    private SquadSource source;
    /** 来源本能模板 ID（从本能模板创建时记录，用于 HITL 反馈反查）。 */
    private UUID sourcePatternId;
    private List<PhaseNode> phases;
    private Boolean active;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private Double successRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 领域行为 =====

    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public void activate() { this.active = true; this.updatedAt = LocalDateTime.now(); }
    public void deactivate() { this.active = false; this.updatedAt = LocalDateTime.now(); }

    /** 记录一次使用（usageCount + 1，更新 lastUsedAt）。 */
    public void recordUsage() {
        this.usageCount = (this.usageCount != null ? this.usageCount : 0) + 1;
        this.lastUsedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 根据执行结果更新成功率。 */
    public void recordExecutionOutcome(boolean success) {
        double total = (this.usageCount != null ? this.usageCount : 0);
        double currentRate = this.successRate != null ? this.successRate : 0.0;
        if (total > 0) {
            this.successRate = (currentRate * (total - 1) + (success ? 1.0 : 0.0)) / total;
        }
    }

    /** 判断 Squad 名称是否包含在任务描述中（精确匹配）。 */
    public boolean matchesTaskByName(String taskDescription) {
        if (taskDescription == null || this.name == null) return false;
        return taskDescription.toLowerCase().contains(this.name.toLowerCase());
    }

    /** 判断 Squad 的关键词是否匹配任务描述（分词匹配，阈值 30%）。 */
    public boolean matchesTaskByKeywords(String taskDescription) {
        if (taskDescription == null || this.description == null) return false;
        String task = taskDescription.toLowerCase();
        String[] keywords = this.description.toLowerCase().split("[\\s，,、]+");
        long matchCount = 0;
        for (String kw : keywords) {
            if (kw.length() > 1 && task.contains(kw)) matchCount++;
        }
        return keywords.length > 0 && (double) matchCount / keywords.length > 0.3;
    }

    /** 添加阶段到指定位置。 */
    public void addPhase(int index, PhaseNode phase) {
        if (this.phases == null) {
            this.phases = new java.util.ArrayList<>();
        }
        this.phases.add(index, phase);
        renumberPhases();
    }

    /** 移除指定位置的阶段。 */
    public void removePhase(int index) {
        if (this.phases != null && index >= 0 && index < this.phases.size()) {
            this.phases.remove(index);
            renumberPhases();
        }
    }

    /** 校验不变量：Squad 必须包含至少一个阶段。 */
    public void validateInvariants() {
        if (this.phases == null || this.phases.isEmpty()) {
            throw new IllegalStateException("Squad 必须包含至少一个阶段");
        }
    }

    /** 重新编号阶段序号（从 0 开始连续递增）。 */
    private void renumberPhases() {
        if (this.phases != null) {
            for (int i = 0; i < this.phases.size(); i++) {
                this.phases.get(i).setPhase(i);
            }
        }
    }

    /** 从请求更新字段（仅更新非 null 值）。 */
    public void updateFrom(String name, String description, SquadMode mode, UUID projectId, List<PhaseNode> phases, Boolean active) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (mode != null) this.mode = mode;
        if (projectId != null) this.projectId = projectId;
        if (phases != null) this.phases = phases;
        if (active != null) this.active = active;
        this.updatedAt = LocalDateTime.now();
    }
}
