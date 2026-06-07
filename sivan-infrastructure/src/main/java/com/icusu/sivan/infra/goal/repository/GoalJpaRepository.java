package com.icusu.sivan.infra.goal.repository;

import com.icusu.sivan.infra.goal.entity.GoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * goals 表数据访问接口。
 */
@Repository
public interface GoalJpaRepository extends JpaRepository<GoalEntity, UUID> {

    List<GoalEntity> findByAccountId(UUID accountId);

    List<GoalEntity> findByAccountIdAndStatus(UUID accountId, String status);

    Optional<GoalEntity> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
