package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.domain.forest.tree.node.FileSnapshotNode;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FileSnapshotNode} 单元测试。
 */
class FileSnapshotNodeTest {

    @Test
    void constructorSetsFields() {
        var node = new FileSnapshotNode("/path/to/file.txt", "file content");
        assertNotNull(node.nodeId());
        assertEquals("/path/to/file.txt", node.filePath());
        assertEquals("file content", node.content());
    }

    @Test
    void nodeTypeIsFileSnapshot() {
        var node = new FileSnapshotNode("id1", "/path", "content");
        assertEquals("file_snapshot", node.nodeType());
    }

    @Test
    void isLeaf() {
        var node = new FileSnapshotNode("id1", "/path", "content");
        assertTrue(node.isLeaf());
        assertTrue(node.children().isEmpty());
    }

    @Test
    void metadataCanBeSet() {
        var node = new FileSnapshotNode("id1", "/path", "content");
        node.setMetadata(Map.of("size", 1024, "hash", "abc123"));
        assertEquals(1024, node.metadata().get("size"));
        assertEquals("abc123", node.metadata().get("hash"));
    }

    @Test
    void parentChildRelationship() {
        var parent = new InnerGoalNode(com.icusu.sivan.common.Mode.SEQUENTIAL,
                java.util.List.of());
        var child = new FileSnapshotNode("id1", "/path", "content");
        child.setParent(parent);
        child.setOrder(0);
        assertEquals(parent, child.parent());
        assertEquals(0, child.order());
    }
}
