package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.List;
import java.util.UUID;

/**
 * 森林仓储接口 — 领域层定义，基础设施层实现。
 * <p>
 * 所有方法显式携带 {@code accountId}，禁止 ThreadLocal 隐式传递。
 */
public interface ForestRepository {

    // ===== Forest =====

    Forest findForestById(UUID forestId, UUID accountId);

    void saveForest(Forest forest, UUID accountId);

    /** 按账号查询所有森林执行记录（按创建时间倒序）。 */
    List<Forest> listByAccountId(UUID accountId);

    /** 按对话 ID 查询关联的森林执行记录（按时间倒序）。 */
    List<Forest> findByConversationId(UUID conversationId, UUID accountId);

    // ===== TreeNode =====

    /**
     * 加载一棵子树。
     *
     * @param rootNodeId 子树的根节点 ID
     * @param accountId  账户 ID
     * @return 子树根节点，含所有子孙节点
     */
    TreeNode findSubtree(String rootNodeId, UUID accountId);

    void saveNode(TreeNode node, UUID forestId, UUID accountId);

    /**
     * 批量保存一棵子树 — 按 BATCH_SIZE 分组 INSERT，替代逐条 saveNode。
     */
    void saveTree(TreeNode root, UUID forestId, UUID accountId);

    void updateNodeStatus(String nodeId, NodeStatus status, UUID accountId);

    /** 更新节点状态 + 执行详情（耗时、token）。 */
    void updateNodeDetails(String nodeId, NodeStatus status, UUID accountId,
                           Integer durationMs, Integer totalTokens);
}
