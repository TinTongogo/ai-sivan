package com.icusu.sivan.domain.agent;

import com.icusu.sivan.common.enums.SkillStatus;
import com.icusu.sivan.common.enums.SkillType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 技能实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill implements SkillDefinition {

    private UUID skillId;
    private UUID accountId;
    private UUID projectId;
    private String skillCode;
    private String name;
    private String displayName;
    private String description;
    private String content;
    private String category;
    private List<String> tags;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private SkillType skillType;
    private SkillStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 领域行为 =====

    /** 归档技能。 */
    public void archive() {
        this.status = SkillStatus.ARCHIVED;
        this.updatedAt = LocalDateTime.now();
    }

    /** 激活技能。 */
    public void activate() {
        this.status = SkillStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }


    /** 判断是否激活状态。 */
    public boolean isActive() {
        return this.status == SkillStatus.ACTIVE;
    }

    /** 记录一次使用（usageCount + 1，更新 lastUsedAt）。 */
    public void recordUsage() {
        this.usageCount = (this.usageCount != null ? this.usageCount : 0) + 1;
        this.lastUsedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 从请求更新字段（仅更新非 null 值）。 */
    public void updateFrom(String name, String displayName, String description, String content, String category, List<String> tags, UUID projectId, SkillStatus status) {
        if (name != null) this.name = name;
        if (displayName != null) this.displayName = displayName;
        if (description != null) this.description = description;
        if (content != null) this.content = content;
        if (category != null) this.category = category;
        if (tags != null) this.tags = tags;
        if (projectId != null) this.projectId = projectId;
        if (status != null) this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
}
