package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.*;

/**
 * 调试访问者 — 递归遍历树，输出带缩进的文本表示。
 * <p>
 * 使用方式：{@code DebugVisitor.dump(root)} 返回完整树文本。
 */
public class DebugVisitor implements ForestVisitor {

    private final StringBuilder sb = new StringBuilder();
    private int indent = 0;

    @Override
    public void visitInnerGoal(InnerGoalNode node) {
        appendLine(node);
        indent++;
        for (TreeNode child : node.children()) {
            child.accept(this);
        }
        indent--;
    }

    @Override
    public void visitTask(TaskNode node) {
        appendLine(node);
    }

    @Override
    public void visitSynthesis(SynthesisNode node) {
        appendLine(node);
    }

    @Override
    public void visitMessage(MessageNode node) {
        appendLine(node);
    }

    @Override
    public void visitMemory(MemoryNode node) {
        appendLine(node);
    }

    @Override
    public void visitFileSnapshot(FileSnapshotNode node) {
        appendLine(node);
    }

    @Override
    public void visitContextBlock(ContextBlockNode node) {
        appendLine(node);
    }

    private void appendLine(TreeNode node) {
        sb.append("  ".repeat(Math.max(0, indent)));
        sb.append(node.nodeType());
        String nid = node.nodeId();
        sb.append(" [").append(nid.length() > 8 ? nid.substring(0, 8) : nid).append("…]");
        if (node instanceof ExecutableNode en) {
            sb.append(" status=").append(en.status());
        }
        if (node instanceof ContentNode cn) {
            String content = cn.content();
            if (content != null && !content.isEmpty()) {
                sb.append(" content=\"").append(truncate(content, 80)).append("\"");
            }
        }
        sb.append('\n');
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public String result() {
        return sb.toString();
    }

    /** 便利方法：一次调用即返回树文本。 */
    public static String dump(TreeNode root) {
        DebugVisitor v = new DebugVisitor();
        root.accept(v);
        return v.result();
    }
}
