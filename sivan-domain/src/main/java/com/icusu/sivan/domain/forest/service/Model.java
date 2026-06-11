package com.icusu.sivan.domain.forest.service;

import java.util.List;

/**
 * 模型容器 — 一个 provider 实例持有的所有能力集合。
 * <p>
 * 例如同一个 OpenAI 账号可以同时支持 Chat + ImageGen + SpeechSynth。
 */
public interface Model {

    /** 模型标识。与 provider 配置中的 model 字段对应。 */
    String modelId();

    /** 注册在同一个 Model 下的所有能力。 */
    List<Capability> capabilities();

    /** 按能力类型查找。返回 null 表示本模型不支持该能力。 */
    @SuppressWarnings("unchecked")
    default <T extends Capability> T as(Class<T> type) {
        return (T) capabilities().stream()
                .filter(c -> type.isAssignableFrom(c.getClass()))
                .findFirst()
                .orElse(null);
    }
}
