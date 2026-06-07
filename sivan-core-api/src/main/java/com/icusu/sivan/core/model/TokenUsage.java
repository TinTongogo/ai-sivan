package com.icusu.sivan.core.model;

public record TokenUsage(int promptTokens, int completionTokens, int totalTokens, int thinkingTokens) {
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

    public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0);
    }
}
