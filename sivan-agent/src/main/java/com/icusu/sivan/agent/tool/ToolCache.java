package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 工具列表缓存 — 带 TTL + 失效机制。
 * <p>
 * 按 accountId 缓存工具列表，缓存 TTL 默认 30 秒。
 * 当工具注册/注销时调用 {@link #invalidate()} 或 {@link #invalidate(UUID)} 手动失效。
 */
@Component
public class ToolCache {

    private static final Logger log = LoggerFactory.getLogger(ToolCache.class);

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile boolean globallyInvalidated = false;

    private record CacheEntry(List<ToolSpec> tools, Instant createdAt) {}

    /**
     * 获取缓存（命中且未过期则返回，否则调用 loader 加载）。
     */
    public List<ToolSpec> get(UUID accountId, Supplier<List<ToolSpec>> loader) {
        if (globallyInvalidated) {
            cache.clear();
            globallyInvalidated = false;
        }
        CacheEntry entry = cache.get(accountId);
        if (entry != null && !isExpired(entry)) {
            return entry.tools;
        }
        List<ToolSpec> tools = loader.get();
        cache.put(accountId, new CacheEntry(tools, Instant.now()));
        return tools;
    }

    /** 使指定账户的缓存失效。 */
    public void invalidate(UUID accountId) {
        cache.remove(accountId);
    }

    /** 使全部缓存失效。 */
    public void invalidate() {
        globallyInvalidated = true;
    }

    private boolean isExpired(CacheEntry entry) {
        return entry.createdAt.plus(DEFAULT_TTL).isBefore(Instant.now());
    }
}
