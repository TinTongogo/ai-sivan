package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ForestExecutionLogJpaRepository extends JpaRepository<ForestExecutionLogEntity, UUID> {
}
