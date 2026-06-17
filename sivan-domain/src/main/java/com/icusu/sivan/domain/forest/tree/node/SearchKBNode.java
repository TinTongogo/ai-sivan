package com.icusu.sivan.domain.forest.tree.node;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;

import java.util.List;
import java.util.Map;

/**
 * 知识库搜索叶子节点 — 出现在 GoalTree 中需要查询知识库的阶段（10-知识库与RAG §4.5）。
 * <p>
 * nodeType = "kb_search"，由 {@link com.icusu.sivan.agent.forest.SearchKBLeafExecutor} 执行。
 * 查询参数通过 metadata 传递：query（搜索关键词）、kbName（目标知识库，null=全部）、topK（返回条数）。
 */
public class SearchKBNode implements ContentNode, ExecutableNode {

    private final String nodeId;
    private final String content;
    private TreeNode parent;
    private int order;
    private Map<String, Object> metadata;

    private Mode mode;
    private NodeStatus status;

    public SearchKBNode(String nodeId, String query, String kbName, int topK) {
        this.nodeId = nodeId;
        this.content = query != null ? query : "";
        this.mode = Mode.NONE;
        this.status = NodeStatus.PENDING;
        this.metadata = new java.util.HashMap<>();
        if (kbName != null && !kbName.isBlank()) {
            this.metadata.put("kbName", kbName);
        }
        this.metadata.put("topK", String.valueOf(topK > 0 ? topK : 5));
    }

    @Override public String nodeId() { return nodeId; }
    @Override public TreeNode parent() { return parent; }
    @Override public void setParent(TreeNode parent) { this.parent = parent; }
    @Override public int order() { return order; }
    @Override public void setOrder(int order) { this.order = order; }
    @Override public String nodeType() { return "kb_search"; }
    @Override public List<TreeNode> children() { return List.of(); }
    @Override public boolean isExecutable() { return true; }

    @Override public Mode mode() { return mode; }
    @Override public NodeStatus status() { return status; }
    @Override public void setStatus(NodeStatus status) { this.status = status; }

    @Override public String content() { return content; }
    @Override public Map<String, Object> metadata() { return metadata; }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new java.util.HashMap<>();
    }

    @Override
    public void accept(ForestVisitor visitor) {
        // SearchKBNode 暂不涉及深度遍历
    }
}
