package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestAgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ForestAgentMessageJpaRepository extends JpaRepository<ForestAgentMessageEntity, UUID> {
}
