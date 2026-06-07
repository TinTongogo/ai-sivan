package com.icusu.sivan.web.shared.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 基于 Exchange 属性的 SecurityContext 存储仓库 — 无状态、无 Session。
 */
@Component
public class ExchangeAttributeSecurityContextRepository implements ServerSecurityContextRepository {

    private static final String ATTR_KEY = ExchangeAttributeSecurityContextRepository.class.getName() + ".CONTEXT";

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        exchange.getAttributes().put(ATTR_KEY, context);
        return Mono.empty();
    }

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        SecurityContext ctx = (SecurityContext) exchange.getAttributes().get(ATTR_KEY);
        return Mono.justOrEmpty(ctx);
    }
}
