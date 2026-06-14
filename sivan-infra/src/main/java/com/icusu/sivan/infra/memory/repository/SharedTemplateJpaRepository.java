package com.icusu.sivan.infra.memory.repository;

import com.icusu.sivan.infra.memory.entity.SharedTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 共享模板表数据访问接口。
 */
@Repository
public interface SharedTemplateJpaRepository extends JpaRepository<SharedTemplateEntity, UUID> {

    List<SharedTemplateEntity> findByOwnerAccountIdOrderByCreatedAtDesc(UUID ownerAccountId);

    List<SharedTemplateEntity> findByVisibilityOrderByCreatedAtDesc(String visibility);

    List<SharedTemplateEntity> findByVisibilityAndOwnerAccountIdNotOrderByCreatedAtDesc(
            String visibility, UUID ownerAccountId);
}
