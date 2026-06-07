package com.icusu.sivan.orch.executor;

import java.util.List;
import java.util.UUID;

/**
 * 持久化策略 — PhaseCallbacks 的持久化职责拆分。
 */
public interface PersistenceStrategy {

    void saveContract(UUID executionId, UUID accountId, int phaseIndex,
                      String sourceAgent, String targetAgent, String content);

    default void saveAgentCheckpoints(UUID executionId, int phaseIndex,
                                      List<AgentCheckpoint> checkpoints) {}

    default List<AgentCheckpoint> loadAgentCheckpoints(UUID executionId, int phaseIndex) {
        return List.of();
    }
}
