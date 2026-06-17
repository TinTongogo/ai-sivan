package com.icusu.sivan.domain.forest.tree;

import java.util.List;
import java.util.Map;

/**
 * 有内容节点 — 提供文本和元数据。
 * <p>
 * {@link TreeNode#content()} 和 {@link TreeNode#metadata()} 已在基接口定义，
 * ContentNode 仅保留 {@link #setMetadata} 方法。
 */
public interface ContentNode extends TreeNode {

    /** 设置元数据（构建或更新时使用） */
    void setMetadata(Map<String, Object> metadata);

    @Override
    default void putMetadata(String key, Object value) {
        metadata().put(key, value);
    }

    @Override
    default String metadataString(String key) {
        Object v = metadata().get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    default Integer metadataInt(String key) {
        Object v = metadata().get(key);
        return v instanceof Integer i ? i : null;
    }

    @Override
    default Number metadataNumber(String key) {
        Object v = metadata().get(key);
        return v instanceof Number n ? n : null;
    }

    @Override
    default List<?> metadataList(String key) {
        Object v = metadata().get(key);
        return v instanceof List<?> l ? l : null;
    }
}
