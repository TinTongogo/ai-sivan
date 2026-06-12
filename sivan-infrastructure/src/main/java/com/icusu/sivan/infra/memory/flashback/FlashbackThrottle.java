package com.icusu.sivan.infra.memory.flashback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Flashback 频率限制 — 防止刷屏。
 * <p>
 * 同一用户每分钟最多 1 条，每小时最多 5 条。
 */
@Component
public class FlashbackThrottle {

    private static final Logger log = LoggerFactory.getLogger(FlashbackThrottle.class);

    private final Map<UUID, WindowCounter> minuteCounters = new ConcurrentHashMap<>();
    private final Map<UUID, WindowCounter> hourCounters = new ConcurrentHashMap<>();

    /** 是否允许推送。 */
    public boolean allowPush(UUID accountId, double relevance) {
        if (relevance < 0.6) return false;

        long now = System.currentTimeMillis();

        WindowCounter minute = minuteCounters.computeIfAbsent(accountId, k -> new WindowCounter(60_000));
        if (!minute.tryAcquire(now)) {
            log.debug("[Flashback] 每分钟限流: accountId={}", accountId.toString().substring(0, 8));
            return false;
        }

        WindowCounter hour = hourCounters.computeIfAbsent(accountId, k -> new WindowCounter(3_600_000));
        if (!hour.tryAcquire(now)) {
            log.debug("[Flashback] 每小时限流: accountId={}", accountId.toString().substring(0, 8));
            return false;
        }

        return true;
    }

    /** 清理过期计数器（定时任务调用）。 */
    public void clean() {
        long now = System.currentTimeMillis();
        minuteCounters.values().removeIf(w -> now - w.windowStart.get() > 60_000);
        hourCounters.values().removeIf(w -> now - w.windowStart.get() > 3_600_000);
    }

    static class WindowCounter {
        final long windowMs;
        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        final AtomicInteger count = new AtomicInteger(0);
        static final int MAX_PER_MIN = 1;
        static final int MAX_PER_HOUR = 5;

        WindowCounter(long windowMs) { this.windowMs = windowMs; }

        synchronized boolean tryAcquire(long now) {
            if (now - windowStart.get() > windowMs) {
                windowStart.set(now);
                count.set(0);
            }
            int max = windowMs == 60_000 ? MAX_PER_MIN : MAX_PER_HOUR;
            return count.incrementAndGet() <= max;
        }
    }
}
