package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.GoalTreeTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 模板表数据访问接口。
 */
@Repository
public interface TemplateJpaRepository extends JpaRepository<GoalTreeTemplateEntity, UUID> {

    List<GoalTreeTemplateEntity> findByAccountIdOrderByUpdatedAtDesc(UUID accountId);
}
