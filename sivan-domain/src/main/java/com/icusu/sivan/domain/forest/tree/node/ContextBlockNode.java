package com.icusu.sivan.domain.forest.tree.node;

import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.*;

/**
 * 自定义上下文块节点 — 承载外部上下文信息（如 RAG 结果、工具输出等）。
 * <p>
 * 用于上下文管理：将外部系统返回的上下文内容包装为树节点。
 * 实现接口：{@link TreeNode} + {@link ContentNode}。
 */
public class ContextBlockNode implements TreeNode, ContentNode {

    private final String nodeId;
    private final String blockType;
    private final String content;
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private int order;

    public ContextBlockNode(String blockType, String content) {
        this(UUID.randomUUID().toString(), blockType, content);
    }

    public ContextBlockNode(String nodeId, String blockType, String content) {
        this.nodeId = nodeId;
        this.blockType = blockType;
        this.content = content;
    }

    /** 上下文块类型（如 "rag", "tool_output", "summary"）。 */
    public String blockType() { return blockType; }

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
    public String nodeType() { return "context_block"; }

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
    public void accept(ForestVisitor v) { v.visitContextBlock(this); }
}
