package com.icusu.sivan.domain.forest.tree.node;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.CompressibleNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.*;

/**
 * 内部目标节点 — 对应一个"里程碑"，通过 {@link Mode} 编排子节点。
 * <p>
 * 实现接口：{@link ExecutableNode} + {@link CompressibleNode}（可执行、可压缩、无文本内容）。
 */
public class InnerGoalNode implements ExecutableNode, CompressibleNode {

    private final String nodeId;
    private final Mode mode;
    private List<TreeNode> children;
    private final Map<String, Object> metadata;
    private TreeNode parent;
    private NodeStatus status;
    private int order;

    // ——— CompressibleNode 字段 ———
    private double importance;
    private long estimateSubtreeTokens = -1;

    public InnerGoalNode(Mode mode, List<? extends TreeNode> children) {
        this(UUID.randomUUID().toString(), mode, children, NodeStatus.PENDING);
    }

    public InnerGoalNode(String nodeId, Mode mode, List<? extends TreeNode> children, NodeStatus status) {
        this.nodeId = nodeId;
        this.mode = mode;
        this.status = status;
        this.importance = 0.5;
        this.metadata = new HashMap<>();

        // 构建父子关系
        List<TreeNode> safe = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            TreeNode child = children.get(i);
            child.setParent(this);
            child.setOrder(i);
            safe.add(child);
        }
        this.children = Collections.unmodifiableList(safe);
    }

    /**
     * 替换子节点列表（用于 Repository 从 DB 重建树时组装父子关系）。
     */
    public void replaceChildren(List<TreeNode> newChildren) {
        this.children = Collections.unmodifiableList(new ArrayList<>(newChildren));
    }

    // ===== TreeNode =====

    @Override
    public String nodeId() { return nodeId; }

    @Override
    public TreeNode parent() { return parent; }

    @Override
    public void setParent(TreeNode parent) { this.parent = parent; }

    @Override
    public List<TreeNode> children() { return children; }

    @Override
    public int order() { return order; }

    @Override
    public void setOrder(int order) { this.order = order; }

    @Override
    public String nodeType() { return "inner_goal"; }

    // ===== ExecutableNode =====

    @Override
    public Mode mode() { return mode; }

    @Override
    public NodeStatus status() { return status; }

    @Override
    public void setStatus(NodeStatus status) {
        this.status = status;
        onStatusChanged();
    }

    // ===== CompressibleNode =====

    @Override
    public double importance() { return importance; }

    public void importance(double importance) { this.importance = importance; }

    @Override
    public long estimateSelfTokens() { return 0; }

    @Override
    public long estimateSubtreeTokens() { return estimateSubtreeTokens; }

    public void estimateSubtreeTokens(long v) { this.estimateSubtreeTokens = v; }

    @Override
    public void invalidateTokenCache() { this.estimateSubtreeTokens = -1; }

    // ===== Metadata 支持（用于 reasoning 等展示信息） =====

    @Override
    public Map<String, Object> metadata() { return metadata; }

    public void putMetadata(String key, Object value) { metadata.put(key, value); }

    public String metadataString(String key) {
        Object v = metadata.get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    public void accept(ForestVisitor v) { v.visitInnerGoal(this); }
}
