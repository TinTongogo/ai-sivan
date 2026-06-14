package com.icusu.sivan.infra.account.repository;

import com.icusu.sivan.infra.account.entity.ProfileChangeLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 画像变更日志 JPA 仓储。
 */
@Repository
public interface ProfileChangeLogJpaRepository extends JpaRepository<ProfileChangeLogEntity, UUID> {

    /** 查询指定用户最近的变更记录。 */
    List<ProfileChangeLogEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);
}
