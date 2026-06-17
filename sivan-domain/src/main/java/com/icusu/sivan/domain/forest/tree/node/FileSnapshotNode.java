package com.icusu.sivan.domain.forest.tree.node;

import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.*;

/**
 * 文件快照节点 — 记录某个时间点的文件内容快照。
 * <p>
 * 用于上下文管理：Agent 读取文件后，将文件快照作为上下文节点保留。
 * 实现接口：{@link TreeNode} + {@link ContentNode}。
 */
public class FileSnapshotNode implements TreeNode, ContentNode {

    private final String nodeId;
    private final String filePath;
    private final String content;
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private int order;

    public FileSnapshotNode(String filePath, String content) {
        this(UUID.randomUUID().toString(), filePath, content);
    }

    public FileSnapshotNode(String nodeId, String filePath, String content) {
        this.nodeId = nodeId;
        this.filePath = filePath;
        this.content = content;
    }

    /** 文件路径。 */
    public String filePath() { return filePath; }

    // ===== TreeNode =====

    @Override
    public String nodeId() { return nodeId; }

    @Override
    public TreeNode parent() { return parent; }

    @Override
    public void setParent(TreeNode parent) { this.parent = parent; }

    @Override
    public List<TreeNode> children() { return List.of(); }

    @Override
    public boolean isLeaf() { return true; }

    @Override
    public int order() { return order; }

    @Override
    public void setOrder(int order) { this.order = order; }

    @Override
    public String nodeType() { return "file_snapshot"; }

    // ===== ContentNode =====

    @Override
    public String content() { return content; }

    @Override
    public Map<String, Object> metadata() { return metadata; }

    @Override
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata.clear();
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
    }

    @Override
    public void accept(ForestVisitor v) { v.visitFileSnapshot(this); }
}
