package com.icusu.sivan.domain.memory;

import java.util.Optional;
import java.util.UUID;

/**
 * 探索状态仓储接口。
 * 持久化 ε-greedy 探索决策器的调用计数和探索状态。
 */
public interface IExplorationStateRepository {

    /** 按 accountId 查找探索状态。返回 Optional 允许首次查询时创建默认值。 */
    Optional<ExplorationState> findById(UUID accountId);

    /** 保存或更新探索状态。 */
    void save(ExplorationState state);

    /** 删除账户的探索状态。 */
    void deleteById(UUID accountId);
}
