package com.icusu.sivan.infra.memory.adapter;

import com.icusu.sivan.domain.memory.ExplorationState;
import com.icusu.sivan.domain.memory.IExplorationStateRepository;
import com.icusu.sivan.infra.memory.entity.ExplorationStateEntity;
import com.icusu.sivan.infra.memory.repository.ExplorationStateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 探索状态仓储适配器。将 JPA Entity 转换为 Domain 值对象。
 */
@Component
@RequiredArgsConstructor
public class ExplorationStateRepositoryAdapter implements IExplorationStateRepository {

    private final ExplorationStateJpaRepository jpaRepository;

    @Override
    public Optional<ExplorationState> findById(UUID accountId) {
        return jpaRepository.findById(accountId)
                .map(e -> new ExplorationState(e.getAccountId(), e.getCallCount(), e.getLastExplorationCall()));
    }

    @Override
    public void save(ExplorationState state) {
        jpaRepository.save(ExplorationStateEntity.builder()
                .accountId(state.getAccountId())
                .callCount(state.getCallCount())
                .lastExplorationCall(state.getLastExplorationCall())
                .build());
    }

    @Override
    public void deleteById(UUID accountId) {
        jpaRepository.deleteById(accountId);
    }
}
