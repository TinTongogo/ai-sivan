package com.icusu.sivan.infra.security;

import com.icusu.sivan.domain.security.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 沙箱管理器 — 统一的安全入口。
 * <p>
 * 所有外部操作（文件读写、Shell 执行、HTTP 请求）都通过此入口。
 * 流程：查找策略 → 校验 → 审计 → 限时执行。
 * <p>
 * 实现设计文档 05-沙箱与安全 第 3.1 节。
 */
@Component
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);
    private static final int MAX_CONCURRENT = 20;

    private final List<Policy<?>> policies;
    private final AuditManager audit;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    private final Map<Class<?>, Duration> timeouts = new HashMap<>();

    public SandboxManager(AuditManager audit,
                          List<Policy<?>> policyList) {
        this.audit = audit;
        this.policies = policyList;
        timeouts.put(FileRead.class, Duration.ofSeconds(30));
        timeouts.put(FileWrite.class, Duration.ofSeconds(30));
        timeouts.put(ShellExec.class, Duration.ofSeconds(60));
        timeouts.put(HttpRequest.class, Duration.ofSeconds(15));
    }

    /**
     * 统一执行入口。校验 + 审计 + 限时执行。
     *
     * @param action 动作
     * @param ctx    安全上下文
     * @return 执行结果
     */
    @SuppressWarnings("unchecked")
    public <T extends Action> Mono<ActionResult> execute(T action, SecurityContext ctx) {
        // 并发配额检查
        if (isOverloaded()) {
            audit.recordDenied(action, ctx, "并发数超过限制: " + MAX_CONCURRENT);
            return Mono.just(new ActionResult(false, "系统繁忙，请稍后重试", null));
        }

        try {
            // 查找匹配的策略并校验
            Policy<T> policy = findPolicy(action);
            policy.validate(action, ctx);
            audit.recordAllowed(action, ctx);

            Duration timeout = timeouts.getOrDefault(action.getClass(), Duration.ofSeconds(30));
            activeCount.incrementAndGet();
            return executeAction(action, timeout)
                    .doFinally(s -> activeCount.decrementAndGet());
        } catch (PolicyViolationException e) {
            audit.recordDenied(action, ctx, e.getMessage());
            return Mono.just(new ActionResult(false, e.getMessage(), null));
        }
    }

    public boolean isOverloaded() {
        return activeCount.get() >= MAX_CONCURRENT;
    }

    public int activeCount() { return activeCount.get(); }

    @SuppressWarnings("unchecked")
    private <T extends Action> Policy<T> findPolicy(T action) {
        return (Policy<T>) policies.stream()
                .filter(p -> p.actionType().isInstance(action))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无匹配策略: " + action.getClass().getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    private <T extends Action> Mono<ActionResult> executeAction(T action, Duration timeout) {
        // 实际执行委托给对应的处理器
        return switch (action) {
            case FileRead read -> executeFileRead(read);
            case FileWrite write -> executeFileWrite(write);
            case HttpRequest http -> executeHttp(http);
            default -> Mono.just(new ActionResult(false, "不支持的动作类型: " + action.getClass().getSimpleName(), null));
        };
    }

    private Mono<ActionResult> executeFileRead(FileRead read) {
        return Mono.fromCallable(() -> {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(read.path())));
                return new ActionResult(true, null, content);
            } catch (Exception e) {
                return new ActionResult(false, "文件读取失败: " + e.getMessage(), null);
            }
        }).timeout(Duration.ofSeconds(30));
    }

    private Mono<ActionResult> executeFileWrite(FileWrite write) {
        return Mono.fromCallable(() -> {
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Paths.get(write.path()), write.content());
                return new ActionResult(true, null, null);
            } catch (Exception e) {
                return new ActionResult(false, "文件写入失败: " + e.getMessage(), null);
            }
        }).timeout(Duration.ofSeconds(30));
    }

    private Mono<ActionResult> executeHttp(HttpRequest http) {
        return Mono.fromCallable(() -> {
            try {
                var url = new java.net.URI(http.url()).toURL();
                var conn = url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(5000);
                String result = new String(conn.getInputStream().readAllBytes());
                return new ActionResult(true, null, result);
            } catch (Exception e) {
                return new ActionResult(false, "HTTP 请求失败: " + e.getMessage(), null);
            }
        }).timeout(Duration.ofSeconds(15));
    }

    public record ActionResult(boolean success, String message, Object data) {}
}
