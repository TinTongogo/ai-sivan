package com.icusu.sivan.agent.prompt;

/**
 * 知识库查询提示词 — 查询改写、分块等。
 * 统一以「灵枢（Sivan）」为唯一人格。
 */
public final class KnowledgePrompts {

    private KnowledgePrompts() {}

    /**
     * 查询改写 user prompt — 将用户问题改写为 2-3 个语义变体提高召回率。
     *
     * @param originalQuery 用户原始查询
     * @return 格式化的提示词文本
     */
    public static String rewriteQuery(String originalQuery) {
        return "你是一个搜索查询改写专家。请将用户的搜索问题改写为 2-3 个不同表述的变体，\n"
                + "覆盖同义词、不同表述角度、更精确的术语，以帮助搜索引擎找到更多相关结果。\n\n"
                + "要求：\n"
                + "- 每行一个变体，不要编号\n"
                + "- 保持原意的同时使用不同的词汇和表述方式\n"
                + "- 如果原问题很短，补充上下文相关的扩展\n"
                + "- 直接输出变体，不要解释\n\n"
                + "用户问题：" + PromptUtils.escapeUserInput(originalQuery);
    }
}
