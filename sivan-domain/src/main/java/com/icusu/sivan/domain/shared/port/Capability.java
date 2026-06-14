package com.icusu.sivan.domain.shared.port;

/**
 * 能力标记接口 — 一个 Provider 可以持有多个能力。
 * <p>
 * 每种能力有独立的接口但共享 Model 容器。
 */
public interface Capability {
    /** 能力所属模型 ID。 */
    String modelId();
}
