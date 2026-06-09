package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.domain.forest.service.ModelCapabilities;
import com.icusu.sivan.domain.forest.service.ModelCapabilities.Capability;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.infra.forest.model.EchoAdapter;
import com.icusu.sivan.infra.forest.model.ModelRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider 工厂 — 根据 accountId 加载真实 LLM Provider 并注册到 V2 ModelRegistry。
 * <p>
 * 启动时注册 EchoAdapter 作为全局兜底；
 */
@Component
public class ProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderFactory.class);

    private final ModelRegistry registry;
    private final DefaultModelRouter router;

    public ProviderFactory(ModelRegistry registry, DefaultModelRouter router) {
        this.registry = registry;
        this.router = router;
    }

    @PostConstruct
    void init() {
        var caps = new ModelCapabilities("echo", "local",
                Set.of(Capability.STREAM), 2048, 0, 8192, List.of(), 0, 0);
        registry.register(new EchoAdapter("echo", caps));
        log.info("[ProviderFactory] EchoAdapter 已注册");
    }

    /**
     * 为指定账户加载真实 LLM Provider，注册到 V2 ModelRegistry。
     * <p>
     * 幂等：如果该账户的模型已注册则跳过。
     */
    public void loadForAccount(UUID accountId) {
        LlmProvider provider;
        try {
            provider = router.getDefaultProvider(accountId);
        } catch (Exception e) {
            log.warn("[ProviderFactory] 账户 {} 无可用 LLM Provider，使用 EchoAdapter 兜底: {}",
                    accountId, e.getMessage());
            return;
        }

        String modelId = provider.getPrimaryModelName();
        if (registry.contains(modelId)) {
            return; // 已注册，跳过
        }

        try {
            var v1Model = router.getDefaultModel(accountId);
            var adapter = new OpenAiModelAdapter(v1Model, provider);
            registry.register(adapter);
            log.info("[ProviderFactory] 已加载 LLM: account={} model={} provider={}",
                    accountId, modelId, provider.getProviderType());
        } catch (Exception e) {
            log.error("[ProviderFactory] 加载 LLM 失败 account={}: {}", accountId, e.getMessage());
        }
    }
}
