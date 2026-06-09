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

    /** 递归加载子树 — 从 rootNodeId 开始往下查所有层级。 */
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT * FROM forest_nodes
                WHERE node_id = :rootNodeId AND forest_id = :forestId
                UNION ALL
                SELECT fn.* FROM forest_nodes fn
                INNER JOIN subtree st ON fn.parent_node_id = st.node_id
                WHERE fn.forest_id = :forestId
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
}
