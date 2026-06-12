package com.icusu.sivan.domain.agent;

import com.icusu.sivan.common.enums.AgentStatus;
import com.icusu.sivan.common.enums.AgentType;
import com.icusu.sivan.domain.tool.ToolRequirement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 智能体配置实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    private UUID agentId;
    private UUID accountId;
    private String agentName;
    private String displayName;
    private String description;
    private String category;
    /** 系统提示词。默认空字符串，持久化层有 NOT NULL 约束，写入前必须设值。 */
    @Builder.Default
    private String systemPrompt = "";
    private String craftDeclaration;
    private List<String> skillIds;
    private ToolRequirement toolRequirements;
    private AgentType agentType;
    private AgentStatus status;
    private Integer version;
    private String createdBy;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 领域行为 =====

    /** 激活智能体。 */
    public void activate() {
        this.status = AgentStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    /** 停用智能体。 */
    public void deactivate() {
        this.status = AgentStatus.INACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    /** 添加技能引用。 */
    public void addSkill(String skillId) {
        if (this.skillIds == null) {
            this.skillIds = new java.util.ArrayList<>();
        }
        if (!this.skillIds.contains(skillId)) {
            this.skillIds.add(skillId);
            this.version = (this.version != null ? this.version : 0) + 1;
        }
    }

    /** 移除技能引用。 */
    public void removeSkill(String skillId) {
        if (this.skillIds != null && this.skillIds.remove(skillId)) {
            this.version = (this.version != null ? this.version : 0) + 1;
        }
    }


    /** 更新版本号。 */
    public void incrementVersion() {
        this.version = (this.version != null ? this.version : 0) + 1;
        this.updatedAt = LocalDateTime.now();
    }

    /** 记录一次使用（usageCount + 1，更新 lastUsedAt）。 */
    public void recordUsage() {
        this.usageCount = (this.usageCount != null ? this.usageCount : 0) + 1;
        this.lastUsedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 判断是否激活状态。 */
    public boolean isActive() {
        return this.status == AgentStatus.ACTIVE;
    }

    /** 从请求更新字段（仅更新非 null 值），版本号自增。 */
    public void updateFrom(String displayName, String description, String category, String systemPrompt, String craftDeclaration, List<String> skillIds, ToolRequirement toolRequirements, AgentStatus status) {
        if (displayName != null) this.displayName = displayName;
        if (description != null) this.description = description;
        if (category != null) this.category = category;
        if (systemPrompt != null) this.systemPrompt = systemPrompt;
        if (craftDeclaration != null) this.craftDeclaration = craftDeclaration;
        if (skillIds != null) this.skillIds = skillIds;
        if (toolRequirements != null) this.toolRequirements = toolRequirements;
        if (status != null) this.status = status;
        incrementVersion();
    }
}
