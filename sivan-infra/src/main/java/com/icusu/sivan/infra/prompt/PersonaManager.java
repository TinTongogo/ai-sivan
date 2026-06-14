package com.icusu.sivan.infra.prompt;

import com.icusu.sivan.domain.prompt.Persona;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人格管理器 — 管理全局人格定义和当前会话人格。
 * <p>
 * 默认人格为"灵枢"，可通过 switchPersona() 切换。
 */
@Component
public class PersonaManager {

    private final Map<String, Persona> personas = new ConcurrentHashMap<>();

    public PersonaManager() {
        // 注册默认人格
        personas.put("default", Persona.defaultPersona());
        registerDefaultPersonas();
    }

    private void registerDefaultPersonas() {
        personas.put("code", new Persona("code", "代码专家",
                "你是一个资深软件工程师，擅长架构设计、代码审查和技术方案。\n当前日期: {{date}}",
                Map.of("date", LocalDate.now().toString())));
        personas.put("creative", new Persona("creative", "创意助手",
                "你是一个创意写作专家，擅长文案、故事和头脑风暴。\n当前日期: {{date}}",
                Map.of("date", LocalDate.now().toString())));
        personas.put("professional", new Persona("professional", "专业顾问",
                "你是一个专业顾问，回复严谨、结构化、数据驱动。\n当前日期: {{date}}",
                Map.of("date", LocalDate.now().toString())));
    }

    /** 获取人格，不存在时返回 default。 */
    public Persona resolve(String personaId) {
        return personas.getOrDefault(personaId, personas.get("default"));
    }

    /** 注册自定义人格。 */
    public void register(Persona persona) {
        personas.put(persona.personaId(), persona);
    }

    /** 判断人格是否存在。 */
    public boolean exists(String personaId) {
        return personas.containsKey(personaId);
    }
}
