package com.icusu.sivan.domain.security;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志 — 记录每次安全策略校验的结果。
 */
public class AuditLog {
    private UUID logId;
    private UUID accountId;
    private UUID projectId;
    private String actionType;
    private String actionDetail;
    private boolean allowed;
    private String reason;
    private String traceId;
    private Instant timestamp;

    public AuditLog() {}

    public AuditLog(UUID accountId, UUID projectId, String actionType,
                    String actionDetail, boolean allowed, String reason, String traceId) {
        this.logId = UUID.randomUUID();
        this.accountId = accountId;
        this.projectId = projectId;
        this.actionType = actionType;
        this.actionDetail = actionDetail;
        this.allowed = allowed;
        this.reason = reason;
        this.traceId = traceId;
        this.timestamp = Instant.now();
    }

    public UUID getLogId() { return logId; }
    public void setLogId(UUID logId) { this.logId = logId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getActionDetail() { return actionDetail; }
    public void setActionDetail(String actionDetail) { this.actionDetail = actionDetail; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
