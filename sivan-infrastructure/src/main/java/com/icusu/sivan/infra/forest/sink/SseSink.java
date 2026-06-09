package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * SSE Sink — 旁路记录所有 ForestEvent，并通过 Sinks.Many 多播供 SSE 订阅。
 * <p>
 * 合并 LogSink 的日志功能，避免多个 {@link EventSink} 实现导致 Bean 冲突。
 */
@Component
public class SseSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(SseSink.class);

    private final Sinks.Many<ForestEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public void emit(ForestEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("[Event] type={} nodeId={} message={}",
                    event.type(), event.nodeId(), event.message());
        }
        sink.tryEmitNext(event);
    }

    /**
     * 返回事件流 Flux，供 SSE 端点订阅。
     */
    public Flux<ForestEvent> asFlux() {
        return sink.asFlux();
    }
}
