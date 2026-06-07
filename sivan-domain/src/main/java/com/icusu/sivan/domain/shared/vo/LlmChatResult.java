package com.icusu.sivan.domain.shared.vo;

/**
 * LLM 调用结果值对象（领域层共享）。
 */
public record LlmChatResult(String content, String thinking) {

    public static LlmChatResult of(String content, String thinking) {
        return new LlmChatResult(content != null ? content : "", thinking != null ? thinking : "");
    }
}
