package com.icusu.sivan.agent.prompt;

import java.util.Map;

/**
 * 技能创建提示词。统一以「灵枢（Sivan）」为唯一人格。
 */
public final class SkillPrompts {

    private SkillPrompts() {}

    /** 技能创建模板，供外部通过 {@link PromptTemplate} 接口调用。 */
    public static final PromptTemplate SKILL_CREATE_TEMPLATE = new PromptTemplate() {
        @Override
        public Prompt render(Map<String, Object> variables) {
            return skillCreateUser(
                    (String) variables.get("agentName"),
                    (String) variables.get("category"),
                    (String) variables.get("craftDeclaration"),
                    (String) variables.get("systemPrompt"));
        }

        @Override
        public String templateId() { return "skillCreate"; }
    };

    public static final Prompt SKILL_CREATE_SYSTEM = new Prompt(
            "你是灵枢（Sivan），需要为智能体（Agent）生成技能规格。只输出 JSON 数组。",
            Prompt.CacheStrategy.STATIC, 25, Prompt.OutputFormat.JSON_ARRAY);

    public static Prompt skillCreateUser(String agentName, String category, String craftDeclaration, String systemPrompt) {
        String content = "请根据以下智能体（Agent）的角色定位，推断该智能体需要哪些核心技能。\n"
                + "每个技能包含：名称（英文 skill_code 风格）和内容。技能内容需包含：技能定位（该技能在什么场景下使用）、工作流程（关键步骤和方法）、验收标准（如何判断输出质量）。\n"
                + PromptUtils.JSON_ARRAY_ONLY
                + "[\n"
                + "  {\n"
                + "    \"skillCode\": \"skill_name\",\n"
                + "    \"name\": \"技能名称\",\n"
                + "    \"displayName\": \"展示名\",\n"
                + "    \"description\": \"一句话描述\",\n"
                + "    \"category\": \"分类标签，如 code-review、file-ops、data-analysis\",\n"
                + "    \"tags\": [\"标签1\", \"标签2\"],\n"
                + "    \"content\": \"技能内容：定位 + 工作流程 + 验收标准\"\n"
                + "  }\n"
                + "]\n\n"
                + "智能体名称: " + PromptUtils.escapeUserInput(agentName) + "\n"
                + "角色分类: " + (!category.isEmpty() ? category : "未分类") + "\n"
                + "专业技能: " + (craftDeclaration != null && !craftDeclaration.isBlank()
                        ? PromptUtils.escapeUserInput(craftDeclaration) : "无") + "\n"
                + "系统提示词（仅供参考）: " + (systemPrompt != null ? PromptUtils.escapeUserInput(systemPrompt) : "") + "\n\n"
                + "注意：通常 1-3 个技能即可，不要过多。技能必须是通用、可复用的能力——基于角色分类和专业技能声明来设计，"
                + "不要基于系统提示词中的具体任务细节。技能定位应能适用于同类型任务。不出现具体任务指令或文件名。";
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                130 + systemPrompt.length() / 3, Prompt.OutputFormat.JSON_ARRAY);
    }
}
