package com.icusu.sivan.domain.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 路由决策仓储接口。
 */
public interface IRoutingDecisionRepository {

    /** 保存路由决策。 */
    void save(RoutingDecision decision);

    /** 根据 ID 查找路由决策。 */
    Optional<RoutingDecision> findById(UUID decisionId);

    /** 获取指定用户的路由决策列表。 */
    List<RoutingDecision> findByAccount(UUID accountId);

    /** 根据用户和策略查找路由决策。 */
    List<RoutingDecision> findByAccountAndStrategy(UUID accountId, String strategy);

    /** 分页查询路由决策（按创建时间降序）。 */
    List<RoutingDecision> findByAccountPage(UUID accountId, int page, int size);

    /** 分页查询路由决策（按策略过滤，按创建时间降序）。 */
    List<RoutingDecision> findByAccountAndStrategyPage(UUID accountId, String strategy, int page, int size);

    /** 统计指定用户的路由决策总数。 */
    long countByAccount(UUID accountId);

    /** 删除指定路由决策。 */
    void deleteById(UUID decisionId);

    /** 批量删除路由决策。 */
    void deleteBatch(List<UUID> decisionIds);
}
