package com.icusu.sivan.domain.forest.tree;

import java.util.Map;

/**
 * 有内容节点 — 提供文本和元数据。
 * <p>
 * 叶子节点和有文本内容的节点实现此接口。
 */
public interface ContentNode extends TreeNode {

    /** 节点主体文本或摘要 */
    String content();

    /** 附带元数据（领域特定的 KV） */
    Map<String, Object> metadata();

    /** 设置元数据（构建或更新时使用） */
    void setMetadata(Map<String, Object> metadata);
}
