package com.icusu.sivan.core.model;

import java.util.UUID;

/**
 * 模型访问器 — 按账户获取默认对话模型。
 * <p>
 * 提取自 {@code sivan-agent} 的 {@code ModelRouter}，供模式策略等基础设施层组件使用。
 */
@FunctionalInterface
public interface ModelAccessor {

    /** 获取默认对话模型（tag 包含 "chat"）。 */
    Model getDefaultModel(UUID accountId);
}
