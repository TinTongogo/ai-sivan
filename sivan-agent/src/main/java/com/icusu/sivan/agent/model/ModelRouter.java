package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.model.LlmProvider;

import java.util.UUID;

/**
 * 模型路由 — 根据类型（chat / embedding）选择合适的模型。
 */
public interface ModelRouter {
    /** 获取默认对话模型（tag 包含 "chat"） */
    Model getDefaultModel(UUID accountId);
    /** 按 providerId 获取模型 */
    Model getModel(UUID providerId);
    /** 按 providerId 获取提供商配置 */
    LlmProvider getProvider(UUID providerId);
    /** 获取默认对话提供商（tag 包含 "chat"） */
    LlmProvider getDefaultProvider(UUID accountId);
    /** 获取 Embedding 模型（tag 包含 "embedding"） */
    Model getEmbeddingModel(UUID accountId);
    /** 获取 Embedding 提供商（tag 包含 "embedding"） */
    LlmProvider getEmbeddingProvider(UUID accountId);
    /** 获取轻量模型（tag 包含 "light"），未配置时降级到默认对话模型 */
    Model getLightModel(UUID accountId);
    /** 清除模型缓存 */
    void evictCache(UUID providerId);
}
