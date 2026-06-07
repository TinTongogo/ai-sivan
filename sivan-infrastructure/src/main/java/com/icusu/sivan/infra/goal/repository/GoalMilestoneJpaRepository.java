package com.icusu.sivan.infra.goal.repository;

import com.icusu.sivan.infra.goal.entity.GoalMilestoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * goal_milestones 表数据访问接口。
 */
@Repository
public interface GoalMilestoneJpaRepository extends JpaRepository<GoalMilestoneEntity, UUID> {

    List<GoalMilestoneEntity> findByGoalIdOrderBySortOrder(UUID goalId);

    void deleteByGoalId(UUID goalId);
}
