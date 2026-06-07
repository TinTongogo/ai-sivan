package com.icusu.sivan.domain.goal;

import java.util.List;
import java.util.UUID;

/**
 * 目标产物仓储接口。
 */
public interface IGoalArtifactRepository {

    List<GoalArtifact> findByGoalId(UUID goalId);

    void save(GoalArtifact artifact);

    void saveAll(List<GoalArtifact> artifacts);

    void deleteByGoalId(UUID goalId);
}
