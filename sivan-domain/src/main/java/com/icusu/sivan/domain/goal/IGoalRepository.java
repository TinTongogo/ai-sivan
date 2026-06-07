package com.icusu.sivan.domain.goal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 目标仓储接口。
 */
public interface IGoalRepository {

    Optional<Goal> findById(UUID goalId);

    Optional<Goal> findByIdAndAccount(UUID goalId, UUID accountId);

    List<Goal> findAllByAccount(UUID accountId);

    List<Goal> findAllByAccountAndStatus(UUID accountId, String status);

    Optional<Goal> findByConversationId(UUID conversationId);

    void save(Goal goal);

    void update(Goal goal);

    void delete(UUID goalId);
}
