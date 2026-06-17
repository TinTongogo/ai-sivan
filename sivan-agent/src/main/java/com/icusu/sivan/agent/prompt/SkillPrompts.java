package com.icusu.sivan.agent.prompt;

/**
 * 技能创建提示词。统一以「灵枢（Sivan）」为唯一人格。
 */
public final class SkillPrompts {

    private SkillPrompts() {}

    /**
     * 根据任务描述创建技能的 user prompt。
     * <p>
     * 当无任何可用技能时调用，LLM 根据任务内容生成通用可复用的技能定义。
     */
    public static String taskCreatePrompt(String taskContent) {
        return "你是一个技能设计专家。根据以下任务描述，创建一个适合的技能定义。\n\n"
                + "任务描述: " + PromptUtils.escapeUserInput(taskContent) + "\n\n"
                + "请以 JSON 格式返回，严格包含以下字段：\n"
                + "- name: 技能名称（英文小写，可用下划线，如 \"math_calculation\"）\n"
                + "- displayName: 技能显示名称（中文）\n"
                + "- description: 技能描述（一句话，中文）\n"
                + "- category: 技能分类（英文，如 \"calculation\"）\n"
                + "- content: 技能详细内容（markdown 格式，含定位、工作流、验收标准，200-500 字，中文）\n"
                + "- tags: 标签列表（JSON 数组格式，如 [\"数学\",\"计算\"]）\n\n"
                + "只返回 JSON，不要其他内容。";
    }
}
