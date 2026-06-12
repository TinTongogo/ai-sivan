package com.icusu.sivan.infra.security;

import com.icusu.sivan.domain.security.AuditLog;
import com.icusu.sivan.domain.security.AuditLogRepository;
import com.icusu.sivan.domain.security.Action;
import com.icusu.sivan.domain.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计管理器 — 记录每次安全策略校验的结果。
 * 所有通过/拒绝的操作都写入审计日志。
 */
@Component
public class AuditManager {

    private static final Logger log = LoggerFactory.getLogger(AuditManager.class);

    private final AuditLogRepository auditRepo;

    public AuditManager(AuditLogRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    public void recordAllowed(Action action, SecurityContext ctx) {
        AuditLog audit = new AuditLog(
                ctx.accountId(), ctx.projectId(),
                action.getClass().getSimpleName(),
                action.toString(), true, null, null);
        auditRepo.save(audit);
        log.debug("[审计] 允许 {}: {}", action.getClass().getSimpleName(), action);
    }

    public void recordDenied(Action action, SecurityContext ctx, String reason) {
        AuditLog audit = new AuditLog(
                ctx.accountId(), ctx.projectId(),
                action.getClass().getSimpleName(),
                action.toString(), false, reason, null);
        auditRepo.save(audit);
        log.warn("[审计] 拒绝 {}: {} (原因: {})", action.getClass().getSimpleName(), action, reason);
    }
}
