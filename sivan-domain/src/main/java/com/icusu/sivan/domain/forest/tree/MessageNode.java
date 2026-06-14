package com.icusu.sivan.domain.forest.tree;

import com.icusu.sivan.domain.forest.port.ForestVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息节点 — 对话消息，只读不可执行。
 * <p>
 * 实现接口：{@link TreeNode} + {@link ContentNode}。
 */
public class MessageNode implements TreeNode, ContentNode {

    private final String nodeId;
    private final String content;
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private int order;

    public MessageNode(String content) {
        this(UUID.randomUUID().toString(), content);
    }

    public MessageNode(String nodeId, String content) {
        this.nodeId = nodeId;
        this.content = content;
    }

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
    public String nodeType() { return "message"; }

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
    public void accept(ForestVisitor v) { v.visitMessage(this); }
}
