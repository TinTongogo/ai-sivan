package com.icusu.sivan.domain.forest.vo;

/**
 * 调用场景 — ModelRouter 按场景选择模型。
 */
public enum Intent {
    /** 一般对话，默认模型 */
    CHAT,
    /** 创意写作，偏好高创造力模型 */
    CREATIVE,
    /** 深度分析，偏好高推理能力模型 */
    ANALYSIS
}
