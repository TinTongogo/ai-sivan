package com.icusu.sivan.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LLM 提供商仓储接口。
 */
public interface ILlmProviderRepository {

    /** 根据 ID 查找 LLM 提供商。 */
    Optional<LlmProvider> findById(UUID providerId);

    /** 获取指定用户的所有 LLM 提供商。 */
    List<LlmProvider> findAllByAccount(UUID accountId);

    /** 获取指定用户的活跃 LLM 提供商。 */
    List<LlmProvider> findActiveByAccount(UUID accountId);

    /** 获取指定用户的默认 LLM 提供商。 */
    Optional<LlmProvider> findDefaultByAccount(UUID accountId);

    /** 根据 tag 查找 LLM 提供商（同一 tag 可能有多条记录）。 */
    List<LlmProvider> findByTagsContains(String tag);

    /** 根据账户和 tag 查找 LLM 提供商。 */
    List<LlmProvider> findByAccountAndTagsContains(UUID accountId, String tag);

    /** 保存 LLM 提供商。 */
    void save(LlmProvider provider);

    /** 立即刷新待处理的数据库变更。 */
    void flush();

    /** 删除 LLM 提供商。 */
    void delete(UUID providerId);
}
