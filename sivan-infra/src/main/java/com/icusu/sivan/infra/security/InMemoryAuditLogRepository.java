package com.icusu.sivan.infra.security;

import com.icusu.sivan.domain.security.AuditLog;
import com.icusu.sivan.domain.security.AuditLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final ConcurrentLinkedQueue<AuditLog> store = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, AuditLog> byAccount = new ConcurrentHashMap<>();

    @Override
    public void save(AuditLog log) {
        store.add(log);
        byAccount.put(log.getLogId(), log);
    }

    @Override
    public List<AuditLog> findByAccount(UUID accountId, Instant from, Instant to, int limit) {
        return store.stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .filter(l -> !l.getTimestamp().isBefore(from) && !l.getTimestamp().isAfter(to))
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public long countByAccount(UUID accountId, Instant since) {
        return store.stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .filter(l -> !l.getTimestamp().isBefore(since))
                .count();
    }
}
