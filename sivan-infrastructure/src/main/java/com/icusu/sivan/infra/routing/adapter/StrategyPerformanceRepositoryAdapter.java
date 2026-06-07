package com.icusu.sivan.infra.routing.adapter;

import com.icusu.sivan.domain.routing.IStrategyPerformanceRepository;
import com.icusu.sivan.domain.routing.StrategyPerformance;
import com.icusu.sivan.infra.routing.entity.StrategyPerformanceEntity;
import com.icusu.sivan.infra.routing.repository.StrategyPerformanceJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StrategyPerformanceRepositoryAdapter implements IStrategyPerformanceRepository {

    private final StrategyPerformanceJpaRepository jpa;

    @Override
    public Optional<StrategyPerformance> findByAccountAndStrategy(UUID accountId, String strategy) {
        return jpa.findByAccountIdAndStrategy(accountId, strategy).map(this::toDomain);
    }

    @Override
    public List<StrategyPerformance> findAllByAccount(UUID accountId) {
        return jpa.findAllByAccountId(accountId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void save(StrategyPerformance sp) {
        if (sp.getCreatedAt() == null) sp.setCreatedAt(OffsetDateTime.now());
        if (sp.getUpdatedAt() == null) sp.setUpdatedAt(OffsetDateTime.now());
        jpa.save(toEntity(sp));
    }

    @Override
    @Transactional
    public void update(StrategyPerformance sp) {
        sp.setUpdatedAt(OffsetDateTime.now());
        jpa.save(toEntity(sp));
    }

    @Override
    @Transactional
    public void deleteByAccount(UUID accountId) {
        jpa.deleteAllByAccountId(accountId);
    }

    private StrategyPerformance toDomain(StrategyPerformanceEntity e) {
        return StrategyPerformance.builder()
                .id(e.getId())
                .accountId(e.getAccountId())
                .strategy(e.getStrategy())
                .total(e.getTotal())
                .success(e.getSuccess())
                .sumConfidence(e.getSumConfidence())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private StrategyPerformanceEntity toEntity(StrategyPerformance sp) {
        return StrategyPerformanceEntity.builder()
                .id(sp.getId())
                .accountId(sp.getAccountId())
                .strategy(sp.getStrategy())
                .total(sp.getTotal())
                .success(sp.getSuccess())
                .sumConfidence(sp.getSumConfidence())
                .createdAt(sp.getCreatedAt())
                .updatedAt(sp.getUpdatedAt())
                .build();
    }
}
