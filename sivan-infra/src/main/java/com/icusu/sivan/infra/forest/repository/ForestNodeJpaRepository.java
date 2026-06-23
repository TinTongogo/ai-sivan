package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * forest_nodes 表数据访问接口。
 */
@Repository
public interface ForestNodeJpaRepository extends JpaRepository<ForestNodeEntity, String> {

    /** 查询指定森林的所有节点，按 sort_order 排序。 */
    List<ForestNodeEntity> findByForestIdOrderBySortOrderAsc(UUID forestId);

    /** 查询指定森林中某一父节点的所有子节点。 */
    List<ForestNodeEntity> findByForestIdAndParentNodeIdOrderBySortOrderAsc(UUID forestId, String parentNodeId);

    List<ForestNodeEntity> findByForestIdAndNodeTypeOrderBySortOrder(UUID forestId, String nodeType);

    List<ForestNodeEntity> findByNodeTypeAndStatusOrderBySortOrder(String nodeType, String status);

    /** 按节点类型列出所有节点，无 forestId 过滤（用于跨对话查询如 generationGroup）。 */
    List<ForestNodeEntity> findByNodeTypeOrderBySortOrder(String nodeType);

    // ===== 对话查询方法（conversation 节点替代 forests 表） =====

    /** 按 account_id 列出所有对话节点，按 updated_at 倒序。 */
    @Query(value = "SELECT * FROM forest_nodes WHERE node_type = 'conversation' AND account_id = ?1 ORDER BY updated_at DESC", nativeQuery = true)
    List<ForestNodeEntity> findConversationsByAccount(UUID accountId);

    /** 按 account_id + project_id 列出对话节点。 */
    @Query(value = "SELECT * FROM forest_nodes WHERE node_type = 'conversation' AND account_id = ?1 AND project_id = ?2 ORDER BY updated_at DESC", nativeQuery = true)
    List<ForestNodeEntity> findConversationsByAccountAndProject(UUID accountId, UUID projectId);

    /** 按 account_id 统计对话数。 */
    @Query(value = "SELECT COUNT(*) FROM forest_nodes WHERE node_type = 'conversation' AND account_id = ?1", nativeQuery = true)
    long countConversationsByAccount(UUID accountId);

    /** 按 account_id 列出所有执行树根节点（goal tree 列表）。 */
    @Query(value = "SELECT DISTINCT ON (fn.forest_id) fn.* FROM forest_nodes fn "
            + "WHERE fn.node_type IN ('task','inner_goal') AND fn.account_id = ?1 "
            + "ORDER BY fn.forest_id, fn.created_at DESC", nativeQuery = true)
    List<ForestNodeEntity> findForestRootsByAccount(UUID accountId);

    @Query(value = "SELECT * FROM forest_nodes WHERE node_type = :nodeType AND vector IS NOT NULL ORDER BY vector <=> CAST(:queryVec AS vector) LIMIT :limit", nativeQuery = true)
    List<ForestNodeEntity> semanticSearchMemory(@Param("nodeType") String nodeType, @Param("queryVec") String queryVec, @Param("limit") int limit);

    /** 带层级过滤的语义搜索。 */
    @Query(value = "SELECT * FROM forest_nodes WHERE node_type = 'memory' AND level = :level AND vector IS NOT NULL ORDER BY vector <=> CAST(:queryVec AS vector) LIMIT :limit", nativeQuery = true)
    List<ForestNodeEntity> semanticSearchMemoryByLevel(@Param("level") String level, @Param("queryVec") String queryVec, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM forest_nodes WHERE node_type = ?1 AND status IS DISTINCT FROM 'ARCHIVED'", nativeQuery = true)
    long countByNodeType(String nodeType);

    // ===== 记忆专用查询方法（基于 level/archived/important/scope_id 列） =====

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.level = ?2 ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesByLevel(UUID accountId, String level);

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.scopeId = ?2 ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesByScope(UUID accountId, UUID scopeId);

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.level = ?2 AND fn.scopeId = ?3 ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesByLevelAndScope(UUID accountId, String level, UUID scopeId);

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesByAccount(UUID accountId);

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.archived = FALSE OR fn.archived IS NULL ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesNonArchived();

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.important = TRUE ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMemoriesImportantByAccount(UUID accountId);

    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.important = TRUE")
    long countMemoriesImportantByAccount(UUID accountId);

    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND (fn.archived = FALSE OR fn.archived IS NULL) AND fn.retention < ?2 ORDER BY fn.retention ASC")
    List<ForestNodeEntity> findMemoriesArchivable(UUID accountId, java.math.BigDecimal threshold);

    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1")
    long countMemoriesByAccount(UUID accountId);

    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.level = ?2")
    long countMemoriesByAccountAndLevel(UUID accountId, String level);

    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory' AND fn.accountId = ?1 AND fn.archived = TRUE")
    long countMemoriesArchivedByAccount(UUID accountId);

    @Query(value = "SELECT * FROM forest_nodes WHERE node_type = 'memory' AND account_id = ?1 AND content ILIKE '%' || ?2 || '%' ORDER BY sort_order DESC", nativeQuery = true)
    List<ForestNodeEntity> searchMemoriesByKeyword(UUID accountId, String keyword);

    @Query(value = "SELECT COUNT(*) FROM forest_nodes WHERE node_type = 'memory' AND account_id = ?1 AND content ILIKE '%' || ?2 || '%'", nativeQuery = true)
    long countMemoriesByKeyword(UUID accountId, String keyword);

    /** 递归加载子树 — 从 rootNodeId 开始往下查所有层级（深度保护 1000 层）。 */
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT *, 0 AS depth FROM forest_nodes
                WHERE node_id = :rootNodeId AND forest_id = :forestId
                UNION ALL
                SELECT fn.*, st.depth + 1 FROM forest_nodes fn
                INNER JOIN subtree st ON fn.parent_node_id = st.node_id
                WHERE fn.forest_id = :forestId AND st.depth < 1000
            )
            SELECT * FROM subtree ORDER BY sort_order
            """, nativeQuery = true)
    List<ForestNodeEntity> findSubtree(@Param("rootNodeId") String rootNodeId,
                                       @Param("forestId") UUID forestId);

    /** 按 node_type + created_at 降序查询（用于消息匹配最近的执行树）。 */
    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.forestId = ?1 AND fn.nodeType IN ('task','inner_goal') ORDER BY fn.createdAt DESC")
    List<ForestNodeEntity> findExecutionTreesByForest(UUID forestId);

    /** 删除指定森林中某一父节点下的所有子节点。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ForestNodeEntity WHERE forestId = :forestId AND parentNodeId = :parentNodeId")
    int deleteChildren(@Param("forestId") UUID forestId, @Param("parentNodeId") String parentNodeId);

    /** 删除指定森林的所有节点。 */
    @Modifying
    @Query("DELETE FROM ForestNodeEntity WHERE forestId = :forestId")
    int deleteByForestId(@Param("forestId") UUID forestId);

    /** 查询指定账号的所有根节点（用于启动恢复）。 */
    @Query(value = """
            SELECT fn.* FROM forest_nodes fn
            JOIN forests f ON fn.forest_id = f.forest_id
            WHERE fn.parent_node_id IS NULL
              AND fn.status = :status
              AND f.account_id = :accountId
            ORDER BY fn.updated_at DESC
            """, nativeQuery = true)
    List<ForestNodeEntity> findRootNodesByStatus(@Param("status") String status,
                                                  @Param("accountId") UUID accountId);

    /** 查询所有 RUNNING 状态的执行树根（用于启动恢复，finds 表已废弃，直接查 forest_nodes）。 */
    @Query(value = """
            SELECT fn.* FROM forest_nodes fn
            WHERE fn.node_type IN ('task','inner_goal')
              AND fn.status = :status
              AND fn.parent_node_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM forest_nodes parent
                          WHERE parent.node_id = fn.parent_node_id
                            AND parent.node_type NOT IN ('task','inner_goal'))
            ORDER BY fn.updated_at DESC
            """, nativeQuery = true)
    List<ForestNodeEntity> findAllRootNodesByStatus(@Param("status") String status);

    /** 查询指定节点的下一个待执行兄弟节点（用于断点恢复）。 */
    @Query(value = """
            SELECT * FROM forest_nodes
            WHERE parent_node_id = :parentNodeId
              AND forest_id = :forestId
              AND sort_order > :sortOrder
              AND status = 'PENDING'
            ORDER BY sort_order
            LIMIT 1
            """, nativeQuery = true)
    ForestNodeEntity findNextPendingSibling(@Param("parentNodeId") String parentNodeId,
                                             @Param("forestId") UUID forestId,
                                             @Param("sortOrder") int sortOrder);

    // ===== 消息查询方法（messages 表已合入 forest_nodes） =====

    /** 查询对话中最新 N 条消息（按 sortOrder 降序）。 */
    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message' ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findLatestMessages(@Param("forestId") UUID forestId, Pageable pageable);

    /** 游标分页：查询比 beforeSortOrder 更早的消息（按 sortOrder 降序）。 */
    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message' AND fn.sortOrder < :beforeSortOrder ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findMessagesBeforeSortOrder(@Param("forestId") UUID forestId,
                                                        @Param("beforeSortOrder") int beforeSortOrder,
                                                        Pageable pageable);

    /** 统计对话中比指定 sortOrder 更早的消息数。 */
    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message' AND fn.sortOrder < :beforeSortOrder")
    int countMessagesBeforeSortOrder(@Param("forestId") UUID forestId,
                                     @Param("beforeSortOrder") int beforeSortOrder);

    /** 查询对话中消息的最大 sortOrder（用于新消息自动编号）。 */
    @Query("SELECT COALESCE(MAX(fn.sortOrder), 0) FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message'")
    Optional<Integer> findMaxMessageSortOrder(@Param("forestId") UUID forestId);

    /** 查询记忆节点的最大 sortOrder（用于新记忆自动编号）。 */
    @Query("SELECT COALESCE(MAX(fn.sortOrder), 0) FROM ForestNodeEntity fn WHERE fn.nodeType = 'memory'")
    Optional<Integer> findMaxMemorySortOrder();

    /** 查询对话中最新的一条用户消息。 */
    @Query("SELECT fn FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message' AND fn.role = 'user' ORDER BY fn.sortOrder DESC")
    List<ForestNodeEntity> findLatestUserMessage(@Param("forestId") UUID forestId, Pageable pageable);

    /** 统计对话中的消息数。 */
    @Query("SELECT COUNT(fn) FROM ForestNodeEntity fn WHERE fn.forestId = :forestId AND fn.nodeType = 'message'")
    int countMessagesByForestId(@Param("forestId") UUID forestId);

    /** 全文搜索消息内容。 */
    @Query(value = """
            SELECT fn.* FROM forest_nodes fn
            JOIN forests f ON fn.forest_id = f.forest_id
            WHERE fn.node_type = 'message'
              AND f.account_id = :accountId
              AND fn.content ILIKE '%' || :keyword || '%'
            ORDER BY fn.updated_at DESC LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<ForestNodeEntity> searchMessages(@Param("accountId") UUID accountId,
                                          @Param("keyword") String keyword,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);
}
