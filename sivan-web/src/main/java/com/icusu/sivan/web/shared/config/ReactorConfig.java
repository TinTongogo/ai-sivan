package com.icusu.sivan.web.shared.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.CompletionException;

/**
 * Reactor 全局配置。
 * <p>抑制 MCP 连接失败时 transport 层的 onErrorDropped ERROR 日志。
 * 连接失败已由业务层（McpServerConfigService）捕获并友好提示，无需 Reactor 再打印 ERROR 堆栈。</p>
 */
@Slf4j
@Configuration
public class ReactorConfig {

    @PostConstruct
    void configureReactorHooks() {
        Hooks.onErrorDropped(e -> {
            // 提取根因
            Throwable cause = e;
            if (cause instanceof CompletionException ce && ce.getCause() != null) {
                cause = ce.getCause();
            }
            // 连接类异常（MCP 不可达等）已由业务层处理，以 WARN 级别记录即可
            if (cause instanceof ConnectException || cause instanceof java.nio.channels.UnresolvedAddressException) {
                log.warn("Reactor dropped error (已由业务层处理): {}", cause.toString());
            } else {
                log.error("Reactor dropped error", e);
            }
        });
    }
}
