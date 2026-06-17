package com.icusu.sivan.domain.forest.tree;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.icusu.sivan.domain.forest.port.ForestVisitor;
import com.icusu.sivan.domain.forest.tree.node.*;

import java.util.List;
import java.util.Map;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;

/**
 * 树结构接口 — 所有节点都需要。
 * <p>
 * Jackson 多态序列化支持 {@link #nodeType()} 驱动的类型分派，
 * 用于 GoalTreeTemplate 的 JSONB 存储。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "nodeType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InnerGoalNode.class, name = "inner_goal"),
    @JsonSubTypes.Type(value = TaskNode.class, name = "task"),
    @JsonSubTypes.Type(value = SynthesisNode.class, name = "synthesis"),
    @JsonSubTypes.Type(value = MessageNode.class, name = "message"),
    @JsonSubTypes.Type(value = MemoryNode.class, name = "memory"),
    @JsonSubTypes.Type(value = ContextBlockNode.class, name = "context_block"),
    @JsonSubTypes.Type(value = FileSnapshotNode.class, name = "file_snapshot"),
    @JsonSubTypes.Type(value = SearchKBNode.class, name = "kb_search")
})
public interface TreeNode {

    /** 节点唯一 ID */
    String nodeId();

    /** 父节点，null 表示根 */
    TreeNode parent();

    /** 设置父节点（构建树时由 addChild 调用） */
    void setParent(TreeNode parent);

    /** 子节点列表，空列表表示叶子 */
    List<TreeNode> children();

    /** 在兄弟节点中的序号 */
    int order();

    /** 设置兄弟序号（构建树时由 addChild 调用） */
    void setOrder(int order);

    default boolean isLeaf() {
        return children().isEmpty();
    }

    /**
     * 节点是否是 ExecutableNode — 避免编排策略中 instanceof 检查。
     * ExecutableNode 子类应覆写返回 true。
     */
    default boolean isExecutable() {
        return false;
    }

    /**
     * 节点类型标识 — 与 {@code forest_nodes.node_type} 对应。
     * 各实现类应覆写返回自己的类型名。
     */
    default String nodeType() {
        return this.getClass().getSimpleName();
    }

    /**
     * 向上逐层通知祖先节点使 token 缓存失效。
     * 非 {@code CompressibleNode} 无缓存，空实现即可。
     * 在 CompressibleNode 中重写实现真正的向上传播。
     */
    default void invalidateAncestorTokenCache() {
        // 非 CompressibleNode 无缓存，空操作
    }

    /** 节点主体文本，无内容时返回空串。大部分节点有内容，提到基接口避免 instanceof。 */
    default String content() { return ""; }

    /** 附带元数据（领域特定的 KV）。大部分节点有元数据，提到基接口避免 instanceof。 */
    default Map<String, Object> metadata() { return Map.of(); }

    /** 向 metadata 写入 KV（非 ContentNode 为无操作，避免 instanceof 检查）。 */
    default void putMetadata(String key, Object value) {}

    /** 从 metadata 读取 String 值，不存在或类型不匹配时返回 null（避免调用方 instanceof）。 */
    default String metadataString(String key) { return null; }

    /** 从 metadata 读取 Integer 值，不存在或类型不匹配时返回 null。 */
    default Integer metadataInt(String key) { return null; }

    /** 从 metadata 读取 Number 值，不存在或类型不匹配时返回 null。 */
    default Number metadataNumber(String key) { return null; }

    /** 从 metadata 读取 List 值，不存在或类型不匹配时返回 null。 */
    @SuppressWarnings("unchecked")
    default List<?> metadataList(String key) { return null; }

    /** 编排模式，非 ExecutableNode 默认为 NONE。提到基接口避免 instanceof。 */
    default Mode mode() { return Mode.NONE; }

    /** 生命周期状态，非 ExecutableNode 默认为 PENDING。提到基接口避免 instanceof。 */
    default NodeStatus status() { return NodeStatus.PENDING; }

    /**
     * 接受访问者 — 将具体节点类型委派到 {@link ForestVisitor#visitTask(TaskNode)} 等 typed 方法。
     * <p>
     * 各实现类应：{@code visitor.visitXxx(this)}。
     * 替代 {@code instanceof} 运行时判断。
     */
    void accept(ForestVisitor visitor);
}
