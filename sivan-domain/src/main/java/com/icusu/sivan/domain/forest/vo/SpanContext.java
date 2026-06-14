package com.icusu.sivan.domain.forest.vo;

import java.time.Instant;
import java.util.UUID;

/**
 * Span 上下文 — 在 ExecutionContext 中传递，贯穿树的递归执行。
 * <p>
 * 根节点创建 root span，子节点创建 child span（traceId 继承，spanId 新生成）。
 */
public class SpanContext {

    private final String traceId;
    private final String parentSpanId;
    private final String spanId;
    private final Instant startTime;

    public SpanContext(String traceId, String parentSpanId, String spanId, Instant startTime) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.spanId = spanId;
        this.startTime = startTime;
    }

    /** 创建根 SpanContext。 */
    public static SpanContext root() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return new SpanContext(id, null, id, now);
    }

    /** 创建子 SpanContext（继承 traceId，新 spanId）。 */
    public SpanContext child(String nodeId) {
        return new SpanContext(traceId, spanId, traceId + "." + nodeId, Instant.now());
    }

    public String traceId() { return traceId; }
    public String parentSpanId() { return parentSpanId; }
    public String spanId() { return spanId; }
    public Instant startTime() { return startTime; }

    @Override
    public String toString() {
        return "SpanContext{" + "traceId='" + traceId.substring(0, 8) + "...'}";
    }
}
