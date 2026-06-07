package com.icusu.sivan.infra.tool.repository;

import com.icusu.sivan.infra.tool.entity.ToolMatchLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ToolMatchLogJpaRepository extends JpaRepository<ToolMatchLogEntity, UUID> {
}
