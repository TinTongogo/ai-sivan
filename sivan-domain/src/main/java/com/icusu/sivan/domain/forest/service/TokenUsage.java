package com.icusu.sivan.domain.forest.service;

/**
 * token 用量值对象。
 */
public record TokenUsage(int inputTokens, int outputTokens, int thinkingTokens, int totalTokens) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);
}
