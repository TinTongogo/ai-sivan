package com.icusu.sivan.domain.memory;

import java.util.List;
import java.util.UUID;

/**
 * 记忆检索策略接口，定义"从记忆库中检索最相关条目"的契约。
 *
 * @param <T> 检索结果类型
 */
@FunctionalInterface
public interface MemoryRetrievalStrategy<T> {

    /**
     * 检索最相关的记忆条目。
     *
     * @param query     查询文本（对话上下文或任务描述）
     * @param accountId 账户 ID
     * @param limit     最大返回数
     * @return 按相关度降序的结果列表
     */
    List<T> retrieve(String query, UUID accountId, int limit);
}
