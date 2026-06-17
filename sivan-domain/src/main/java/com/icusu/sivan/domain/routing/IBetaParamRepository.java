package com.icusu.sivan.domain.routing;

import java.util.Optional;
import java.util.UUID;

/**
 * Beta 参数仓储 — 用于 Tier 0 精确命中 + Tier 2 Thompson 采样。
 */
public interface IBetaParamRepository {

    /** 按 accountId + featureHash 查询某个 agent 的 Beta 参数。 */
    Optional<BetaParam> findByKey(UUID accountId, String featureHash, String agentName);

    /** 按 accountId + featureHash 查询所有候选 agent 的 Beta 参数（Tier 2 用）。 */
    java.util.List<BetaParam> findAllByKey(UUID accountId, String featureHash);

    /** UPSERT：执行完成后更新 Beta 参数。 */
    void upsert(UUID accountId, String featureHash, String agentName, boolean success);

    /** 删除指定 Agent 的所有 Beta 参数（Agent 删除时清理）。 */
    void deleteByAgent(UUID accountId, String agentName);
}
