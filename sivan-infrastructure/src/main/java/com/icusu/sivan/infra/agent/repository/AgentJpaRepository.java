package com.icusu.sivan.infra.agent.repository;

import com.icusu.sivan.infra.agent.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 智能体表数据访问接口。
 */
@Repository
public interface AgentJpaRepository extends JpaRepository<AgentEntity, UUID> {

    Optional<AgentEntity> findByAccountIdAndAgentName(UUID accountId, String agentName);

    Optional<AgentEntity> findByAccountIdAndProjectIdAndAgentName(UUID accountId, UUID projectId, String agentName);

    List<AgentEntity> findByAccountId(UUID accountId);

    List<AgentEntity> findByAccountIdAndProjectId(UUID accountId, UUID projectId);

    boolean existsByAccountIdAndAgentNameAndAgentIdNot(UUID accountId, String agentName, UUID excludeId);

    long countByAccountId(UUID accountId);
}
