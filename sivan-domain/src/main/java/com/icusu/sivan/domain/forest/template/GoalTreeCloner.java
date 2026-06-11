package com.icusu.sivan.domain.forest.template;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.service.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * GoalTree 克隆器 — 通过 ForestVisitor 模式深拷贝执行树。
 * <p>
 * 遍历原始树，为每个节点创建新实例（重置 status 为 PENDING，清空 completedAt）。
 */
public class GoalTreeCloner implements ForestVisitor {

    private ExecutableNode result;

    public ExecutableNode getResult() { return result; }

    @Override
    public void visitInnerGoal(InnerGoalNode node) {
        List<ExecutableNode> clonedChildren = new ArrayList<>();
        for (TreeNode child : node.children()) {
            if (child instanceof ExecutableNode en) {
                GoalTreeCloner cloner = new GoalTreeCloner();
                en.accept(cloner);
                clonedChildren.add(cloner.getResult());
            }
        }
        InnerGoalNode cloned = new InnerGoalNode(node.nodeId(), node.mode(), clonedChildren, NodeStatus.PENDING);
        cloned.setOrder(node.order());
        result = cloned;
    }

    @Override
    public void visitTask(TaskNode node) {
        TaskNode cloned = new TaskNode(node.content());
        cloned.setOrder(node.order());
        if (node instanceof ContentNode cn) {
            cloned.metadata().putAll(cn.metadata());
        }
        result = cloned;
    }

    @Override
    public void visitSynthesis(SynthesisNode node) {
        SynthesisNode cloned = new SynthesisNode();
        cloned.setOrder(node.order());
        result = cloned;
    }

    @Override
    public void visitMessage(MessageNode node) {
        // 消息节点不应出现在模板中，但为完整性提供实现
        result = null;
    }

    @Override
    public void visitMemory(MemoryNode node) {
        // 记忆节点不应出现在模板中
        result = null;
    }

    @Override
    public void visitContextBlock(ContextBlockNode node) {
        // 上下文块节点不应出现在模板中
        result = null;
    }

    @Override
    public void visitFileSnapshot(FileSnapshotNode node) {
        // 文件快照节点不应出现在模板中
        result = null;
    }
}
