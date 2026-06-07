package com.icusu.sivan.agent.prompt;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 智能体相关提示词。统一以「灵枢（Sivan）」为唯一人格。
 * 动态创建的 Agent 是 Sivan 的子能力，不以独立身份回复用户。
 *
 * <h3>O5 缓存优化</h3>
 * {@link #agentFallbackSystem(String)} 使用 {@link ConcurrentHashMap} 逐 Agent 缓存，
 * 相同 agentName 重复调用时复用 {@link Prompt.CacheStrategy#SESSION_STABLE} 实例。
 */
public final class AgentPrompts {

    private AgentPrompts() {}

    // ============================================================
    // 智能体执行（Sivan 子能力）
    // ============================================================

    /** 逐 Agent 缓存的 system prompt 池。 */
    private static final Map<String, Prompt> AGENT_SYSTEM_PROMPTS = new ConcurrentHashMap<>();

    /** 单智能体执行 system prompt —— 作为 Sivan 的子能力，不直接回复用户。 */
    public static Prompt agentFallbackSystem(String agentName) {
        return AGENT_SYSTEM_PROMPTS.computeIfAbsent(agentName, name -> {
            String escaped = PromptUtils.escapeUserInput(name);
            String content = "你是灵枢（Sivan）调用的一项专业能力，角色是「" +
                    escaped + "」。\n" +
                    "你只负责根据当前指令执行任务，并将结果返回给灵枢（Sivan），由 Sivan 统一回复用户。\n" +
                    "不要以「我」或「" + escaped + "」的身份直接回复用户，也不要主动发起对话。\n" +
                    "请根据以下任务提供专业内容（只输出结果，不加角色前缀）：";
            return new Prompt(content, Prompt.CacheStrategy.SESSION_STABLE,
                    120, Prompt.OutputFormat.FREE_TEXT);
        });
    }

    /** DB 存储的智能体身份回退 —— 极简版本。 */
    public static Prompt agentFallbackIdentity(String agentName) {
        return new Prompt("你是灵枢（Sivan）的一项专业能力，角色为「" +
                PromptUtils.escapeUserInput(agentName) + "」。",
                Prompt.CacheStrategy.STATIC, 20, Prompt.OutputFormat.FREE_TEXT);
    }

    /** 单智能体用户消息。 */
    public static Prompt singleAgentUser(String historyContext, String taskDescription) {
        String content;
        if (historyContext != null && !historyContext.isBlank()) {
            content = historyContext + "\n\n## 当前任务\n" + taskDescription;
        } else {
            content = taskDescription;
        }
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                content.length() / 2, Prompt.OutputFormat.FREE_TEXT);
    }

    // ============================================================
    // Agent 自动创建
    // ============================================================

    public static final Prompt AGENT_CREATE_SYSTEM = new Prompt(
            "你是灵枢（Sivan），需要创建一个新的智能体（Agent）。根据任务需求和角色描述，\n" +
            "生成该智能体的完整配置。只输出 JSON，不加解释。",
            Prompt.CacheStrategy.STATIC, 30, Prompt.OutputFormat.JSON_OBJECT);

    public static Prompt agentCreateUser(String taskType, String agentName, String phaseContext) {
        String content = "需要生成一个名为「" + PromptUtils.escapeUserInput(agentName) + "」的智能体配置。\n" +
                (phaseContext != null && !phaseContext.isBlank()
                    ? "该智能体的角色阶段参考：" + PromptUtils.escapeUserInput(phaseContext) + "\n"
                    : "") +
                "要求：生成的 systemPrompt 必须描述该角色的通用领域专长和核心能力——\n" +
                "需涵盖该角色在同类任务中的通用方法论，不包含当前具体任务的单次指令。\n" +
                "该智能体应能复用于同类任务场景。\n" +
                PromptUtils.JSON_ONLY +
                "{\n" +
                "  \"displayName\": \"展示名\",\n" +
                "  \"description\": \"一句话描述（不超过30字）\",\n" +
                "  \"systemPrompt\": \"系统提示词：定义角色身份和领域专长，通用、可复用，不得包含具体任务内容\",\n" +
                "  \"craftDeclaration\": \"匠心宣言\",\n" +
                "  \"category\": \"分类标签，如 writer、coder、analyst\"\n" +
                "}";
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                100 + (phaseContext != null ? phaseContext.length() / 3 : 0),
                Prompt.OutputFormat.JSON_OBJECT);
    }
}
