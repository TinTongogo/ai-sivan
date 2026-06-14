package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.vo.Span;

/**
 * Span 导出器 — 将执行 Span 写入外部存储（日志 / 指标 / Jaeger / Zipkin）。
 * <p>
 * v2026-06-11：Phase 0 提供日志导出实现，Phase 1+ 可对接 Micrometer / OpenTelemetry。
 */
@FunctionalInterface
public interface SpanExporter {

    /** 导出一个完成的 Span。实现必须保证不抛异常（失败静默）。 */
    void export(Span span);
}
