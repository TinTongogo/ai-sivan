package com.icusu.sivan.domain.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 策略性能统计持久化接口。 */
public interface IStrategyPerformanceRepository {
    Optional<StrategyPerformance> findByAccountAndStrategy(UUID accountId, String strategy);
    List<StrategyPerformance> findAllByAccount(UUID accountId);
    void save(StrategyPerformance sp);
    void update(StrategyPerformance sp);
    /** 删除指定账户的全部策略表现数据。 */
    void deleteByAccount(UUID accountId);
}
