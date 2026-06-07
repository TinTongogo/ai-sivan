package com.icusu.sivan.infra.shared.sse;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理活跃的 LLM 流，使 LLM 调用与 SSE 连接生命周期解耦。
 * <p>
 * 客户端断开 SSE 时 LLM 继续在后台运行并累积内容，
 * 客户端可重新订阅同一 msgId 续接流。
 * <p>
 * 可配置属性（application.yml）：
 * <ul>
 *   <li>{@code sivan.stream.max-events} — Sink 最大回放事件数，默认 5000</li>
 *   <li>{@code sivan.stream.ttl-minutes} — 流超时分钟数，默认 5</li>
 *   <li>{@code sivan.stream.cleanup-interval-ms} — 清理间隔毫秒，默认 60000</li>
 * </ul>
 */
@Slf4j
@Component
public class StreamManager {

    @Value("${sivan.stream.max-events:5000}")
    private int maxEvents = 5000;

    @Value("${sivan.stream.ttl-minutes:5}")
    private int ttlMinutes = 5;

    @Value("${sivan.stream.cleanup-interval-ms:60000}")
    private long cleanupIntervalMs = 60000;

    private final Map<UUID, ActiveStream> streams = new ConcurrentHashMap<>();

    /**
     * 为指定消息创建流，返回 Sink 供生产者写入。
     */
    public Sinks.Many<String> create(UUID msgId) {
        Sinks.Many<String> sink = Sinks.many().replay().limit(maxEvents);
        streams.put(msgId, new ActiveStream(sink, Instant.now()));
        return sink;
    }

    /**
     * 订阅指定消息的流。流不存在或已完成时返回空 Flux。
     */
    public Flux<String> subscribe(UUID msgId) {
        ActiveStream as = streams.get(msgId);
        if (as == null) return Flux.empty();
        return as.sink.asFlux();
    }

    /**
     * 标记流完成。
     */
    public void complete(UUID msgId) {
        ActiveStream as = streams.get(msgId);
        if (as != null) {
            as.sink.emitComplete((signalType, emitResult) -> {
                if (emitResult.isFailure()) {
                    log.warn("Sink emitComplete 失败: msgId={}, result={}", msgId, emitResult);
                }
                return false;
            });
        }
    }

    /**
     * 移除并丢弃指定流。
     */
    public void remove(UUID msgId) {
        streams.remove(msgId);
    }

    /**
     * 指定消息是否有活跃流。
     */
    public boolean isActive(UUID msgId) {
        return streams.containsKey(msgId);
    }

    /**
     * 定期清理超时未完成的陈旧流。
     */
    @Scheduled(fixedDelayString = "${sivan.stream.cleanup-interval-ms:60000}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
        streams.entrySet().removeIf(e -> {
            if (e.getValue().createdAt.isBefore(cutoff)) {
                log.warn("清理超时流: {} (TTL: {}min)", e.getKey(), ttlMinutes);
                return true;
            }
            return false;
        });
    }

    /** 关闭所有活跃流。 */
    @PreDestroy
    public void shutdown() {
        streams.forEach((id, as) -> as.sink.emitComplete((signalType, emitResult) -> {
            if (emitResult.isFailure()) {
                log.warn("Sink emitComplete 失败(关闭): msgId={}, result={}", id, emitResult);
            }
            return false;
        }));
        streams.clear();
    }

    private record ActiveStream(Sinks.Many<String> sink, Instant createdAt) {}
}
