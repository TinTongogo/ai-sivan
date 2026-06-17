package com.icusu.sivan.agent.prompt;

/**
 * 智能体相关提示词。统一以「灵枢（Sivan）」为唯一人格。
 * 动态创建的 Agent 是 Sivan 的子能力，不以独立身份回复用户。
 */
public final class AgentPrompts {

    private AgentPrompts() {}

    // ============================================================
    // Agent 自动创建
    // ============================================================

    public static final Prompt AGENT_CREATE_SYSTEM = new Prompt(
            "你是灵枢（Sivan），需要创建一个新的智能体（Agent）。根据任务需求和角色描述，\n" +
            "生成该智能体的完整配置。只输出 JSON，不加解释。",
            Prompt.CacheStrategy.STATIC, 30, Prompt.OutputFormat.JSON_OBJECT);

    public static Prompt agentCreateUser(String taskType, String taskContent) {
        String content = "根据以下任务内容，创建一个可复用的智能体。\n" +
                "任务内容：" + PromptUtils.escapeUserInput(taskContent) + "\n" +
                "要求：\n" +
                "1. 从任务内容推断该智能体的领域专长和角色定位\n" +
                "2. displayName 应能反映该角色的能力范畴，方便后续复用\n" +
                "3. systemPrompt 描述角色的通用领域专长和核心能力，不包含具体任务指令\n" +
                "4. 该智能体应能复用于同类任务场景，不局限于本次单次指令\n" +
                PromptUtils.JSON_ONLY +
                "{\n" +
                "  \"displayName\": \"展示名（反映领域专长，不含 auto/默认 等占位词）\",\n" +
                "  \"description\": \"一句话描述（不超过30字）\",\n" +
                "  \"systemPrompt\": \"系统提示词：定义角色身份和领域专长，通用、可复用\",\n" +
                "  \"craftDeclaration\": \"匠心宣言\",\n" +
                "  \"category\": \"分类标签，如 writer、coder、analyst\"\n" +
                "}";
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                200 + (taskContent != null ? taskContent.length() / 3 : 0),
                Prompt.OutputFormat.JSON_OBJECT);
    }
}
