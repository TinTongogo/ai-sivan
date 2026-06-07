package com.icusu.sivan.web.shared.security;

import com.icusu.sivan.common.exception.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 解析 {@link CurrentAccountId}，从 exchange attributes 读取 JWT 解析后的 accountId。
 */
@Component
public class CurrentAccountIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAccountId.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter,
                                         BindingContext bindingContext,
                                         ServerWebExchange exchange) {
        UUID accountId = exchange.getAttribute("accountId");
        if (accountId == null) {
            return Mono.error(new UnauthorizedException("未登录或登录已过期"));
        }
        return Mono.just(accountId);
    }
}
