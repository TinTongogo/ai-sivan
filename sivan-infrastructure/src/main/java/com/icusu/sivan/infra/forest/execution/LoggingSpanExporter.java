package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.service.Span;
import com.icusu.sivan.domain.forest.service.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志 Span 导出器 — 将 Span 输出为日志。
 * <p>
 * 默认启用，可通过 {@code sivan.performance.tracing.enabled=false} 关闭。
 * 日志示例：
 * <pre>
 * [Trace] nodeId=task-xxx type=task durationMs=1234 status=completed spanId=xxx
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "sivan.performance.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class LoggingSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger("sivan.trace");

    private static final int MAX_NODE_ID_LEN = 20;

    @Override
    public void export(Span span) {
        if (!log.isInfoEnabled()) return;
        String shortNodeId = span.nodeId() != null && span.nodeId().length() > MAX_NODE_ID_LEN
                ? span.nodeId().substring(0, MAX_NODE_ID_LEN)
                : span.nodeId();
        log.info("[Trace] nodeId={} type={} durationMs={} status={} traceId={} spanId={}",
                shortNodeId, span.nodeType(), span.durationMs(), span.status(),
                truncate(span.traceId(), 8), truncate(span.spanId(), 8));
    }

    private static String truncate(String s, int len) {
        return s != null && s.length() > len ? s.substring(0, len) : s;
    }
}
