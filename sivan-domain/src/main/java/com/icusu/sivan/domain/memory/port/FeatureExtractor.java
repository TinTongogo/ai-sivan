package com.icusu.sivan.domain.memory.port;

import com.icusu.sivan.domain.memory.TaskFeatures;

/**
 * 特征提取器 — 从任务描述中提取结构化特征向量。
 * <p>
 * 这是本能模板匹配的入口：任务描述 → 特征向量 → 匹配模板。
 * 提取的特征不包含原始文本，只包含结构化类型信息。
 */
@FunctionalInterface
public interface FeatureExtractor {

    /**
     * 从任务描述中提取特征向量。
     * @param task 任务描述文本（如 "帮我重构登录模块"）
     * @return 结构化特征向量（仅枚举值，不保留原始文本）
     */
    TaskFeatures extract(String task);
}
