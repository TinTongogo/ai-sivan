package com.icusu.sivan.agent.prompt;

/**
 * 提示词值对象。
 * @param content        提示词文本
 * @param cacheStrategy  缓存策略
 * @param estimatedTokens 粗略 token 估算
 * @param outputFormat   LLM 输出格式约束
 */
public record Prompt(
        String content,
        CacheStrategy cacheStrategy,
        int estimatedTokens,
        OutputFormat outputFormat) {

    public enum CacheStrategy { STATIC, SESSION_STABLE, DYNAMIC }

    public enum OutputFormat { FREE_TEXT, JSON_OBJECT, JSON_ARRAY, SINGLE_NUMBER }

    /** 空提示词。 */
    public static final Prompt EMPTY = new Prompt("", CacheStrategy.DYNAMIC, 0, OutputFormat.FREE_TEXT);

    /** 是否有实际内容。 */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
