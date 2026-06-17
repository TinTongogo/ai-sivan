package com.icusu.sivan.agent.prompt;

/**
 * 用户画像学习提示词 — 从对话中提取技术栈/兴趣标签。
 * 统一以「灵枢（Sivan）」为唯一人格。
 */
public final class ProfilePrompts {

    private ProfilePrompts() {}

    /** 画像提取 system prompt — 从对话中提取用户技术栈偏好和专业技术领域标签。 */
    public static final String EXTRACT_SYSTEM = """
            你是一个用户画像分析师。从以下对话中提取用户的技术栈偏好和专业技术领域标签。
            只输出 JSON（不要 markdown 代码块标记）。

            ## 提取规则
            - 只提取用户明确使用或讨论的技术，不推测
            - 标签尽量具体（如 "Spring Boot" 优于 "Java"）
            - 提取 3-8 个标签，按重要性排序
            - 如果对话中没有技术信息，返回空数组

            ## 输出格式
            {"expertise":["标签1","标签2","标签3"]}

            ## 示例
            对话：帮我写一个 Spring Boot 的 REST API，数据库用 PostgreSQL
            输出：{"expertise":["Spring Boot","REST API","PostgreSQL","Java"]}

            对话：你好，今天天气怎么样？
            输出：{"expertise":[]}""";
}
