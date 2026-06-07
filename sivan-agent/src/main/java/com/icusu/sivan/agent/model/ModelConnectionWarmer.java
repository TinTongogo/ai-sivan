package com.icusu.sivan.agent.model;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.LlmProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 启动时预热 chat 模型连接，消除首次 LLM 调用的 TCP/HTTP 连接建立延迟。
 * <p>
 * 选择 active + isDefault 的 chat provider 建立连接，不依赖特定账户。
 * Embedding 模型轻量且冷启动快，无需预热。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelConnectionWarmer {

    private final ILlmProviderRepository llmProviderRepository;
    private final ModelRouter modelRouter;

    @PostConstruct
    void warmup() {
        Mono.delay(Duration.ofSeconds(3))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(__ -> warmChatProvider(), err -> log.warn("延迟预热调度异常: {}", err.getMessage()));
    }

    /** 预热 chat provider：取 active + 优先 isDefault 的 provider。 */
    private void warmChatProvider() {
        List<LlmProvider> candidates = llmProviderRepository.findByTagsContains("chat").stream()
                .filter(LlmProvider::isActive)
                .toList();
        if (candidates.isEmpty()) {
            log.debug("无活跃 chat provider，跳过预热");
            return;
        }
        LlmProvider p = candidates.stream()
                .filter(prov -> Boolean.TRUE.equals(prov.getIsDefault()))
                .findFirst()
                .orElse(candidates.getFirst());

        try {
            Model model = modelRouter.getModel(p.getProviderId());
            model.chat(
                    List.of(Msg.of(Role.SYSTEM, "ping")),
                    Model.ModelParams.defaults().withMaxTokens(1)
            ).subscribe(
                    resp -> log.debug("模型连接预热成功: {} ({})", p.getPrimaryModelName(), p.getBaseUrl()),
                    err -> log.warn("模型连接预热失败(不影响主流程): {} — {}", p.getPrimaryModelName(), err.getMessage())
            );
        } catch (Exception e) {
            log.warn("模型连接预热异常(不影响主流程): {} — {}", p.getPrimaryModelName(), e.getMessage());
        }
    }
}
