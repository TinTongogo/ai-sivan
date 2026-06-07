package com.icusu.sivan.infra.goal.repository;

import com.icusu.sivan.infra.goal.entity.GoalTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * goal_tasks 表数据访问接口。
 */
@Repository
public interface GoalTaskJpaRepository extends JpaRepository<GoalTaskEntity, UUID> {

    List<GoalTaskEntity> findByGoalIdOrderBySortOrder(UUID goalId);

    List<GoalTaskEntity> findByMilestoneIdOrderBySortOrder(UUID milestoneId);

    void deleteByGoalId(UUID goalId);

    void deleteByMilestoneId(UUID milestoneId);
}
