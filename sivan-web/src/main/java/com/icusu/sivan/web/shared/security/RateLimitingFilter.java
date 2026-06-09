package com.icusu.sivan.web.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 频率限制过滤器（WebFlux）。
 * <p>基于 IP 的固定窗口限流，覆盖登录、注册、知识库搜索等端点。</p>
 */
@Slf4j
@Component
@Order(1)
public class RateLimitingFilter implements WebFilter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final MessageSource messageSource;

    public RateLimitingFilter(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Value("${sivan.auth.rate-limit.max-requests:10}")
    private int maxRequests;

    @Value("${sivan.auth.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /** 限流路径：POST 方法 + 路径前缀匹配。 */
    private static final List<String> RATE_LIMITED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/knowledge-bases/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 仅对 POST 请求 + 匹配路径限流
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return chain.filter(exchange);
        }
        String path = request.getURI().getPath();
        boolean matched = RATE_LIMITED_PATHS.stream().anyMatch(path::startsWith);
        if (!matched) {
            return chain.filter(exchange);
        }

        String ip = getClientIp(request);
        Window window = windows.computeIfAbsent(ip, k -> new Window(windowSeconds));
        if (window.tryAcquire(maxRequests)) {
            return chain.filter(exchange);
        }

        log.warn("请求频率超限: ip={}, path={}", ip, path);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(windowSeconds));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Locale locale = LocaleContextHolder.getLocale();
        String msg = messageSource.getMessage("error.rate-limit.exceeded", null, "请求过于频繁，请稍后再试", locale);
        byte[] body = ("{\"code\":429,\"message\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static String getClientIp(ServerHttpRequest request) {
        var xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    /** 固定窗口计数器。 */
    static class Window {
        private final long windowMillis;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart;

        Window(int windowSeconds) {
            this.windowMillis = windowSeconds * 1000L;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int max) {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMillis) {
                windowStart = now;
                count.set(1);
                return true;
            }
            int current = count.incrementAndGet();
            return current <= max;
        }
    }
}
