package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.domain.forest.tree.node.ContextBlockNode;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextBlockNode} 单元测试。
 */
class ContextBlockNodeTest {

    @Test
    void constructorSetsFields() {
        var node = new ContextBlockNode("rag", "RAG 检索结果内容");
        assertNotNull(node.nodeId());
        assertEquals("rag", node.blockType());
        assertEquals("RAG 检索结果内容", node.content());
    }

    @Test
    void nodeTypeIsContextBlock() {
        var node = new ContextBlockNode("id1", "tool", "output");
        assertEquals("context_block", node.nodeType());
    }

    @Test
    void isLeaf() {
        var node = new ContextBlockNode("id1", "tool", "output");
        assertTrue(node.isLeaf());
        assertTrue(node.children().isEmpty());
    }

    @Test
    void metadataCanBeSet() {
        var node = new ContextBlockNode("id1", "tool", "output");
        node.setMetadata(Map.of("source", "mcp-server-1"));
        assertEquals("mcp-server-1", node.metadata().get("source"));
    }

    @Test
    void parentChildRelationship() {
        var parent = new InnerGoalNode(com.icusu.sivan.common.Mode.SEQUENTIAL,
                java.util.List.of());
        var child = new ContextBlockNode("id1", "tool", "output");
        child.setParent(parent);
        child.setOrder(1);
        assertEquals(parent, child.parent());
        assertEquals(1, child.order());
    }
}
