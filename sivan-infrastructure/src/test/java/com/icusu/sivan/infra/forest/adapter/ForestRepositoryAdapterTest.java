package com.icusu.sivan.infra.forest.adapter;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import com.icusu.sivan.common.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ForestRepositoryAdapter} 集成测试。
 */
@Sql({"/disable-fk.sql", "/seed-test-account.sql"})
@Transactional
class ForestRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ForestRepository forestRepository;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void shouldSaveAndFindForestById() {
        UUID forestId = UUID.randomUUID();
        var forest = new Forest(forestId, ACCOUNT_ID, null, "测试森林", "root-1");
        forestRepository.saveForest(forest, ACCOUNT_ID);

        Forest found = forestRepository.findForestById(forestId, ACCOUNT_ID);
        assertNotNull(found);
        assertEquals(forestId, found.forestId());
        assertEquals("测试森林", found.title());
        assertEquals("root-1", found.rootNodeId());
    }

    @Test
    void shouldReturnNullWhenForestNotFound() {
        Forest found = forestRepository.findForestById(UUID.randomUUID(), ACCOUNT_ID);
        assertNull(found);
    }

    @Test
    void shouldSaveAndLoadTree() {
        UUID forestId = UUID.randomUUID();

        // 构建树：root → [child1, child2]
        var child1 = new TaskNode("任务1");
        var child2 = new TaskNode("任务2");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(child1, child2));

        var forest = new Forest(forestId, ACCOUNT_ID, null, "树测试", root.nodeId());
        forestRepository.saveForest(forest, ACCOUNT_ID);

        forestRepository.saveTree(root, forestId, ACCOUNT_ID);

        // 递归加载子树
        TreeNode loadedRoot = forestRepository.findSubtree(root.nodeId(), ACCOUNT_ID);
        assertNotNull(loadedRoot);
        assertEquals(root.nodeId(), loadedRoot.nodeId());
        assertEquals(2, loadedRoot.children().size());

        // 验证子节点
        TreeNode loadedChild1 = loadedRoot.children().get(0);
        assertEquals("任务1", ((TaskNode) loadedChild1).content());
        assertEquals(root.nodeId(), loadedChild1.parent().nodeId());

        TreeNode loadedChild2 = loadedRoot.children().get(1);
        assertEquals("任务2", ((TaskNode) loadedChild2).content());
    }

    @Test
    void shouldReturnNullWhenSubtreeNotFound() {
        TreeNode root = forestRepository.findSubtree("non-existent", ACCOUNT_ID);
        assertNull(root);
    }

    @Test
    void shouldUpdateNodeStatus() {
        UUID forestId = UUID.randomUUID();
        var node = new TaskNode("状态节点");
        var forest = new Forest(forestId, ACCOUNT_ID, null, "状态测试", node.nodeId());
        forestRepository.saveForest(forest, ACCOUNT_ID);

        forestRepository.saveNode(node, forestId, ACCOUNT_ID);

        // 更新状态
        forestRepository.updateNodeStatus(node.nodeId(), NodeStatus.COMPLETED, ACCOUNT_ID);

        // 加载验证
        TreeNode loaded = forestRepository.findSubtree(node.nodeId(), ACCOUNT_ID);
        assertNotNull(loaded);
        assertEquals(node.nodeId(), loaded.nodeId());
    }

    @Test
    void shouldSaveNodeAndFindInSubtree() {
        UUID forestId = UUID.randomUUID();
        var node = new TaskNode("单独保存的节点");
        var forest = new Forest(forestId, ACCOUNT_ID, null, "单节点测试", node.nodeId());
        forestRepository.saveForest(forest, ACCOUNT_ID);

        forestRepository.saveNode(node, forestId, ACCOUNT_ID);

        TreeNode loaded = forestRepository.findSubtree(node.nodeId(), ACCOUNT_ID);
        assertNotNull(loaded);
        assertEquals("单独保存的节点", ((TaskNode) loaded).content());
    }

    @Test
    void shouldReplaceTreeOnReSave() {
        UUID forestId = UUID.randomUUID();
        var oldChild = new TaskNode("旧任务");
        var root = new InnerGoalNode(Mode.SEQUENTIAL, List.of(oldChild));
        var forest = new Forest(forestId, ACCOUNT_ID, null, "替换测试", root.nodeId());
        forestRepository.saveForest(forest, ACCOUNT_ID);

        // 第一次保存
        forestRepository.saveTree(root, forestId, ACCOUNT_ID);

        // 替换子节点（复用相同 root nodeId）
        var newChild = new TaskNode("新任务");
        var updatedRoot = new InnerGoalNode(root.nodeId(), Mode.SEQUENTIAL, List.of(newChild), NodeStatus.PENDING);
        forestRepository.saveTree(updatedRoot, forestId, ACCOUNT_ID);

        // 验证子节点已被替换
        TreeNode loaded = forestRepository.findSubtree(updatedRoot.nodeId(), ACCOUNT_ID);
        assertNotNull(loaded);
        assertEquals(1, loaded.children().size());
        assertEquals("新任务", ((TaskNode) loaded.children().get(0)).content());
    }
}
