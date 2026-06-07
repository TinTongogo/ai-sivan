package com.icusu.sivan.infra.goal.repository;

import com.icusu.sivan.infra.goal.entity.GoalArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * goal_artifacts 表数据访问接口。
 */
@Repository
public interface GoalArtifactJpaRepository extends JpaRepository<GoalArtifactEntity, UUID> {

    List<GoalArtifactEntity> findByGoalId(UUID goalId);

    void deleteByGoalId(UUID goalId);
}
