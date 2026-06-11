package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * forests 表数据访问接口。
 */
@Repository
public interface ForestJpaRepository extends JpaRepository<ForestEntity, UUID> {

    Optional<ForestEntity> findByForestIdAndAccountId(UUID forestId, UUID accountId);

    Optional<ForestEntity> findByRootNodeIdAndAccountId(String rootNodeId, UUID accountId);

    List<ForestEntity> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
