package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.List;
import java.util.Map;
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

    /** 删除森林及其所有节点。 */
    void deleteForest(UUID forestId, UUID accountId);

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

    // ===== 恢复（09-持久化与恢复 §3.1） =====

    /**
     * 按根节点状态查询所属森林（用于启动恢复）。
     * 返回根节点列表，不含子树。调用方需要时自行调用 {@link #findSubtree} 加载完整树。
     */
    List<TreeNode> findRootNodesByStatus(NodeStatus status, UUID accountId);

    /**
     * 查询某节点的下一个待执行兄弟节点（用于断点恢复）。
     * 按 sort_order 升序找同父节点下第一个 PENDING 状态的后续兄弟节点。
     *
     * @param nodeId   当前节点 ID
     * @param forestId 所属森林 ID
     * @param accountId 账户 ID
     * @return 下一个待执行兄弟节点，不存在则返回 null
     */
    TreeNode findNextSibling(String nodeId, UUID forestId, UUID accountId);

    /** 按类型查询森林中的节点（用于消息列表等场景）。 */
    List<? extends TreeNode> findNodesByType(UUID forestId, String nodeType, UUID accountId);

    /**
     * 更新 ContentNode 的内容 + metadata。
     * 用于执行完成后更新 assistant 消息内容。
     */
    void updateNodeContent(String nodeId, String content, Map<String, Object> metadata, UUID accountId);

    /** 查询某类型节点。 */
    List<? extends TreeNode> findNodesByTypeAndAccount(UUID accountId, String nodeType, int limit);

    /** 根据向量相似度搜索 memory 节点。 */
    List<? extends TreeNode> semanticSearchMemory(UUID accountId, float[] queryVec, int topK, String levelFilter);

    /** 非归档记忆节点的总数（用于遗忘曲线调度）。 */
    long countActiveMemories(UUID accountId);

    /** 更新记忆节点的保留率（遗忘曲线）。 */
    void updateMemoryRetention(String nodeId, double retention, UUID accountId);
}
