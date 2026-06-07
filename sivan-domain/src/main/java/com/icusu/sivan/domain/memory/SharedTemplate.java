package com.icusu.sivan.domain.memory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 共享本能模板。用户可将自己的本能模板共享给其他用户，
 * 支持 PUBLIC（所有人可见）/ TENANT（租户内可见）/ LIST（指定账户）三种可见性。
 */
public class SharedTemplate {

    private UUID templateId;
    private UUID patternId;
    private UUID ownerAccountId;
    private Visibility visibility;
    private UUID projectId;
    private String allowedAccounts;  // JSON 数组字符串，LIST 可见性时指定允许的 account_id 列表
    private String status;           // ACTIVE / ORPHANED
    private String quality;          // NORMAL / LOW_QUALITY
    private int useCount;
    private int successCount;
    private LocalDateTime sharedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Visibility {
        PUBLIC,
        TENANT,
        LIST
    }

    public SharedTemplate() {}

    @lombok.Builder
    public SharedTemplate(UUID templateId, UUID patternId, UUID ownerAccountId,
                          Visibility visibility, UUID projectId, String allowedAccounts,
                          String status, String quality, int useCount, int successCount,
                          LocalDateTime sharedAt, LocalDateTime createdAt,
                          LocalDateTime updatedAt) {
        this.templateId = templateId;
        this.patternId = patternId;
        this.ownerAccountId = ownerAccountId;
        this.visibility = visibility;
        this.projectId = projectId;
        this.allowedAccounts = allowedAccounts;
        this.status = status != null ? status : "ACTIVE";
        this.quality = quality != null ? quality : "NORMAL";
        this.useCount = useCount;
        this.successCount = successCount;
        this.sharedAt = sharedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
    public UUID getPatternId() { return patternId; }
    public void setPatternId(UUID patternId) { this.patternId = patternId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(UUID ownerAccountId) { this.ownerAccountId = ownerAccountId; }
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getAllowedAccounts() { return allowedAccounts; }
    public void setAllowedAccounts(String allowedAccounts) { this.allowedAccounts = allowedAccounts; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public int getUseCount() { return useCount; }
    public void setUseCount(int useCount) { this.useCount = useCount; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public LocalDateTime getSharedAt() { return sharedAt; }
    public void setSharedAt(LocalDateTime sharedAt) { this.sharedAt = sharedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 记录一次使用。 */
    public void recordUsage() { this.useCount++; }

    /** 记录一次成功。 */
    public void recordSuccess() { this.successCount++; }

    /** 标记为孤儿（owner 删除时）。 */
    public void markOrphaned() { this.status = "ORPHANED"; }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean isOrphaned() { return "ORPHANED".equals(status); }
}
