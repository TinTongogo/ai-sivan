package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /** 查询所有根节点（跨账号，用于启动时全局恢复扫描）。 */
    @Query(value = """
            SELECT fn.* FROM forest_nodes fn
            JOIN forests f ON fn.forest_id = f.forest_id
            WHERE fn.parent_node_id IS NULL
              AND fn.status = :status
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
}
