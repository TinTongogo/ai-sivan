package com.icusu.sivan.memory;

import com.icusu.sivan.memory.instinct.InstinctPatternService;

import java.util.List;
import java.util.UUID;

/**
 * 记忆检索策略接口，定义"从记忆库中检索最相关条目"的契约。
 * <p>
 * 当前实现：
 * <ul>
 *   <li>{@link com.icusu.sivan.memory.flashback.FlasbackScanner} — 基于遗忘曲线的情境闪现</li>
 *   <li>{@link InstinctPatternService} — 基于关键词重叠的本能模板匹配</li>
 * </ul>
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
