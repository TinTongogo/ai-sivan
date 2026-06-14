package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestAgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ForestAgentMessageJpaRepository extends JpaRepository<ForestAgentMessageEntity, UUID> {
    List<ForestAgentMessageEntity> findByScopeNodeId(String scopeNodeId);
    List<ForestAgentMessageEntity> findByForestId(UUID forestId);
}
