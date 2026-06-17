package com.icusu.sivan.domain.forest.port;

import com.icusu.sivan.domain.forest.tree.*;
import com.icusu.sivan.domain.forest.tree.node.*;

/**
 * 森林访问者 — 编译期安全的类型分派。
 * <p>
 * 替代 {@code instanceof} 运行时判断。{@link TreeNode#accept(ForestVisitor)}
 * 将具体节点类型委派到对应的 {@code visit*} 方法。
 * <p>
 * 新增节点类型 → 接口增加一个 {@code visit*} 方法 → 所有 Visitor 实现编译报错。
 */
public interface ForestVisitor {

    void visitInnerGoal(InnerGoalNode node);
    void visitTask(TaskNode node);
    void visitSynthesis(SynthesisNode node);
    void visitMessage(MessageNode node);
    void visitMemory(MemoryNode node);
    void visitFileSnapshot(FileSnapshotNode node);
    void visitContextBlock(ContextBlockNode node);
}
