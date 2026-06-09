package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.tree.TreeNode;

/**
 * 节点组装器 — 从持久化数据重建 {@link TreeNode} 实例。
 * <p>
 * 每个节点类型提供一个实现，注册到 {@code NodeAssemblerRegistry}。
 * 新增节点类型 = 新实现类 → 注册，零个已有文件被修改。
 */
@FunctionalInterface
public interface NodeAssembler {

    /**
     * 根据数据库行数据构造节点。
     *
     * @param nodeId     节点 ID
     * @param mode       编排模式（可能为 null）
     * @param status     生命周期状态（可能为 null）
     * @param content    文本内容（可能为 null）
     * @param importance 重要性 [0, 1]（可能为 null）
     * @return 构造完成的节点（未设置 parent/order，由调用方后续组装）
     */
    TreeNode assemble(String nodeId, Mode mode, NodeStatus status, String content, Double importance);
}
