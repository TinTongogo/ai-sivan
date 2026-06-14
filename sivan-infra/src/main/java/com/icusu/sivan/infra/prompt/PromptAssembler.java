package com.icusu.sivan.infra.prompt;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.domain.prompt.Persona;
import com.icusu.sivan.domain.prompt.PromptPack;
import com.icusu.sivan.domain.prompt.PromptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提示词装配器 — 按场景 + 人格装配完整的消息列表。
 * <p>
 * O4 缓存策略：SYSTEM 文本编译后缓存，独立 USER 消息不污染缓存。
 * 输出顺序：[SYSTEM(静态), USER(上下文), USER(用户输入)]
 */
@Component
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    private final PromptStore store;
    private final PersonaManager personaManager;
    private final PromptRenderer renderer;
    private final PromptCache cache;

    public PromptAssembler(PromptStore store, PersonaManager personaManager,
                           PromptRenderer renderer, PromptCache cache) {
        this.store = store;
        this.personaManager = personaManager;
        this.renderer = renderer;
        this.cache = cache;
    }

    /**
     * 装配完整的 LLM 消息列表。
     *
     * @param scene       场景（CHAT / SINGLE_AGENT / SQUAD）
     * @param personaId   人格标识
     * @param userInput   用户输入
     * @param context     动态上下文（历史摘要、记忆等）
     * @param vars        额外模板变量
     * @return [SYSTEM, USER(上下文), USER(用户输入)]
     */
    public List<Msg> assemble(String scene, String personaId,
                               String userInput, String context,
                               Map<String, Object> vars) {
        // 1. 获取人格
        Persona persona = personaManager.resolve(personaId);

        // 2. 获取提示词包
        PromptPack pack = store.findBySceneAndPersona(scene, personaId)
                .orElseGet(() -> createDefaultPack(scene, personaId));

        // 3. SYSTEM：缓存已编译文本（不含人格注入）
        String cacheKey = PromptCache.cacheKey(pack.packId().toString(), pack.version());
        String systemText = cache.get(cacheKey);
        if (systemText == null) {
            systemText = renderer.render(pack.systemTemplate(), vars);
            cache.put(cacheKey, systemText);
        }
        // 人格注入（在缓存命中后执行，避免不同人格读到脏缓存）
        systemText = injectPersona(systemText, persona);

        // 4. 动态上下文 USER 消息
        String userContext = context != null && !context.isBlank()
                ? renderer.render(context, vars) : "";

        // 5. 用户输入 USER 消息
        String userMsg = userInput != null ? userInput : "";

        // 6. 组装（O4 顺序）
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, systemText));
        if (!userContext.isBlank()) {
            msgs.add(Msg.of(Role.USER, userContext));
        }
        msgs.add(Msg.of(Role.USER, userMsg));
        return msgs;
    }

    private String injectPersona(String systemText, Persona persona) {
        if (persona == null) return systemText;
        return persona.injectInto(systemText);
    }

    private PromptPack createDefaultPack(String scene, String personaId) {
        String template = "你是一个 AI 助手，名为灵枢（Sivan）。\n当前日期: {{date}}\n用户: {{name}}";
        PromptPack pack = new PromptPack(scene, personaId, 1, template);
        store.save(pack);
        return pack;
    }
}
