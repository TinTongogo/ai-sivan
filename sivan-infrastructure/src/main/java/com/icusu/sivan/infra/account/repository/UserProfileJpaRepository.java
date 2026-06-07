package com.icusu.sivan.infra.account.repository;

import com.icusu.sivan.infra.account.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户画像 JPA 仓储。
 */
@Repository
public interface UserProfileJpaRepository extends JpaRepository<UserProfileEntity, UUID> {

    /** 根据账户 ID 查找启用的画像。 */
    Optional<UserProfileEntity> findByAccountIdAndActiveTrue(UUID accountId);

    /** 删除账户的所有画像。 */
    void deleteByAccountId(UUID accountId);
}
