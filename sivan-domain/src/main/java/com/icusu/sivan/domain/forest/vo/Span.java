package com.icusu.sivan.domain.forest.vo;

import java.time.Duration;
import java.time.Instant;

/**
 * 执行 Span — 一次节点执行的耗时与状态记录。
 * <p>
 * 不可变值对象，由 TracedExecutor 在每个节点执行时创建。
 */
public class Span {

    private final String traceId;
    private final String parentSpanId;
    private final String spanId;
    private final String nodeId;
    private final String nodeType;
    private final Instant startTime;
    private final long durationMs;
    private final String status;

    public Span(String traceId, String parentSpanId, String spanId,
                String nodeId, String nodeType, Instant startTime,
                long durationMs, String status) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.spanId = spanId;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.startTime = startTime;
        this.durationMs = durationMs;
        this.status = status;
    }

    /** 创建一个已完成的 Span（用于节点执行完毕时记录）。 */
    public static Span finished(String traceId, String parentSpanId, String spanId,
                                String nodeId, String nodeType,
                                Instant startTime, Instant endTime, String status) {
        return new Span(traceId, parentSpanId, spanId, nodeId, nodeType,
                startTime, Duration.between(startTime, endTime).toMillis(), status);
    }

    public String traceId() { return traceId; }
    public String parentSpanId() { return parentSpanId; }
    public String spanId() { return spanId; }
    public String nodeId() { return nodeId; }
    public String nodeType() { return nodeType; }
    public Instant startTime() { return startTime; }
    public long durationMs() { return durationMs; }
    public String status() { return status; }
}
