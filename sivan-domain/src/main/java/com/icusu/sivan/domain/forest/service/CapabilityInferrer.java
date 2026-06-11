package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.service.ModelCapabilities;

/**
 * 能力推断策略 — 根据模型名动态推断能力声明。
 * <p>
 * Strategy 模式替换 v1 {@code ModelCapabilityRegistry} 的硬编码前缀匹配。
 * 每个 Provider 一个实现，新增模型只需新增/修改对应的 Inferrer。
 */
@FunctionalInterface
public interface CapabilityInferrer {

    /**
     * 根据模型名推断能力。返回 null 表示本策略不适用于该模型。
     */
    ModelCapabilities infer(String modelName);
}
