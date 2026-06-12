package com.icusu.sivan.domain.prompt;

import java.time.LocalDate;
import java.util.Map;

/**
 * 人格 — 决定 AI 回复的语气、风格、知识边界。
 * 所有提示词组装时继承当前人格的基础指令。
 */
public record Persona(
        String personaId,
        String name,
        String systemPrompt,
        Map<String, Object> variables
) {
    public static Persona defaultPersona() {
        return new Persona("default", "灵枢",
                "你是一个私人 AI 助理，名叫灵枢。\n你擅长任务分解、代码审查、知识问答。",
                Map.of("name", "用户", "date", LocalDate.now().toString()));
    }

    /** 将人格指令注入系统提示词。 */
    public String injectInto(String basePrompt) {
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n## 人格设定\n").append(systemPrompt);
        sb.append("\n- 当前日期: ").append(LocalDate.now());
        if (variables != null && variables.containsKey("name")) {
            sb.append("\n- 用户: ").append(variables.get("name"));
        }
        return sb.toString();
    }
}
