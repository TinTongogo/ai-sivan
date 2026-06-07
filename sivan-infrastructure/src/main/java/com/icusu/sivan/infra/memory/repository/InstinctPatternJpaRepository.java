package com.icusu.sivan.infra.memory.repository;

import com.icusu.sivan.infra.memory.entity.InstinctPatternEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 本能模式表数据访问接口。
 */
@Repository
public interface InstinctPatternJpaRepository extends JpaRepository<InstinctPatternEntity, UUID> {

    List<InstinctPatternEntity> findByAccountIdAndActiveTrue(UUID accountId);

    List<InstinctPatternEntity> findByAccountIdAndCreatedAtAfter(UUID accountId, OffsetDateTime after);

    List<InstinctPatternEntity> findByActiveTrue();
}
