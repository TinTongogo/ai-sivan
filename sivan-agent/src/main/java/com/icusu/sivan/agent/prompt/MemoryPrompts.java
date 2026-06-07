package com.icusu.sivan.agent.prompt;

/**
 * 记忆与上下文提示词。统一以「灵枢（Sivan）」为唯一人格 —— 作为用户的记忆管家。
 */
public final class MemoryPrompts {

    private MemoryPrompts() {}

    /** 记忆 COLD 层浓缩 system prompt（STATIC）。 */
    public static final Prompt COLD_COMPRESSION_SYSTEM = new Prompt(
            "你是灵枢（Sivan）的记忆管家，负责整理和浓缩对话记忆。\n" +
            "从多条记忆条目中提取关键信息，按以下 JSON 格式输出：\n\n" +
            "{\n" +
            "  \"user_profile\": \"用户身份和偏好摘要（角色、偏好、习惯）\",\n" +
            "  \"decisions\": \"重要决策和约定（已确定的事项、规则、约定）\",\n" +
            "  \"active_tasks\": \"正在进行中的任务（未完成事项、延续的上下文）\"\n" +
            "}\n\n" +
            "原则：只保留用户身份偏好、重要决策约定、未完成的任务。" +
            "忽略日常问候、已完成的细节提问、重复内容。直接输出 JSON，不加解释。",
            Prompt.CacheStrategy.STATIC, 130, Prompt.OutputFormat.JSON_OBJECT);

    /** 构建 COLD 层浓缩的 user prompt。 */
    public static Prompt coldCompressionUser(String rawEntries, int maxTokens) {
        String content = "请将以下记忆条目浓缩为结构化 JSON（不超过" + maxTokens + " tokens）：\n\n" +
                PromptUtils.truncate(rawEntries, 10000);
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                20 + rawEntries.length() / 3, Prompt.OutputFormat.JSON_OBJECT);
    }

    /** 构建对话交换摘要的提示词。 */
    public static Prompt exchangeSummary(String userContent, String assistantContent) {
        StringBuilder sb = new StringBuilder(
                "请根据以下对话片段提取关键信息，用一句话概括（不超过80字），\n" +
                "同时包含用户的意图和灵枢（Sivan）的回复要点：\n\n");
        sb.append("用户: ").append(PromptUtils.escapeUserInput(userContent)).append("\n");
        if (assistantContent != null && !assistantContent.isBlank()) {
            sb.append("助手: ").append(PromptUtils.escapeUserInput(assistantContent));
        }
        String content = sb.toString();
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                30 + content.length() / 3, Prompt.OutputFormat.FREE_TEXT);
    }
}
