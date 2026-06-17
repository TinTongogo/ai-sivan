package com.icusu.sivan.domain.forest.tree.node;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 合成节点 — CONSENSUS 模式最后一步，由 LLM 综合所有前置 children 的输出。
 * <p>
 * 实现接口：{@link ExecutableNode} + {@link ContentNode}（可执行、有内容、不参与 token 估算）。
 */
public class SynthesisNode implements ExecutableNode, ContentNode {

    private final String nodeId;
    private final Mode mode;
    private final Map<String, Object> metadata = new HashMap<>();
    private TreeNode parent;
    private NodeStatus status;
    private int order;
    private String content;

    public SynthesisNode() {
        this(UUID.randomUUID().toString(), "", NodeStatus.PENDING);
    }

    public SynthesisNode(String nodeId, String content, NodeStatus status) {
        this.nodeId = nodeId;
        this.content = content;
        this.status = status;
        this.mode = Mode.NONE;
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
    public String nodeType() { return "synthesis"; }

    // ===== ExecutableNode =====

    @Override
    public Mode mode() { return mode; }

    @Override
    public NodeStatus status() { return status; }

    @Override
    public void setStatus(NodeStatus status) { this.status = status; }

    // ===== ContentNode =====

    @Override
    public String content() { return content; }

    public void content(String content) { this.content = content; }

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
    public void accept(ForestVisitor v) { v.visitSynthesis(this); }
}
