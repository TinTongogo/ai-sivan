package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 树匹配器 — 将用户输入映射为不同深度的执行树。
 * <p>
 * 匹配链：启发式 → Embedding → LLM（逐步增强），
 * 返回不同深度的 {@link ExecutableNode}：
 * <ul>
 *   <li>短消息 → 单步 {@link com.icusu.sivan.domain.forest.tree.TaskNode}</li>
 *   <li>含步骤关键词 → 多步 {@link com.icusu.sivan.domain.forest.tree.InnerGoalNode}</li>
 *   <li>复杂描述 → LLM 拆解为多级树</li>
 * </ul>
 */
public interface TreeMatcher {

    /**
     * 匹配用户输入，返回一棵可执行的节点树。
     *
     * @param input     用户输入文本
     * @param accountId 账户 ID
     * @return 匹配到的执行树根节点
     */
    Mono<ExecutableNode> match(String input, UUID accountId);
}
