package com.icusu.sivan.infra.orchestration.repository;

import com.icusu.sivan.infra.orchestration.entity.SquadEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Squad 表数据访问接口。
 */
@Repository
public interface SquadJpaRepository extends JpaRepository<SquadEntity, UUID> {

    List<SquadEntity> findByAccountId(UUID accountId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT s FROM SquadEntity s WHERE s.accountId = :accountId AND (s.active IS NULL OR s.active = TRUE)")
    List<SquadEntity> findByAccountIdAndActiveTrue(UUID accountId);

    List<SquadEntity> findByAccountIdAndProjectId(UUID accountId, UUID projectId);

    long countByAccountId(UUID accountId);

    Page<SquadEntity> findByAccountId(UUID accountId, Pageable pageable);

    Page<SquadEntity> findByAccountIdAndProjectId(UUID accountId, UUID projectId, Pageable pageable);
}
