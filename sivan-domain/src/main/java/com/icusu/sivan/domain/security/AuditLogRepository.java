package com.icusu.sivan.domain.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审计日志仓储接口。
 */
public interface AuditLogRepository {
    void save(AuditLog log);
    List<AuditLog> findByAccount(UUID accountId, Instant from, Instant to, int limit);
    long countByAccount(UUID accountId, Instant since);
}
