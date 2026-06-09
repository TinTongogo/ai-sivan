package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错误日志 Sink — 装饰器，拦截 {@link ForestEvent.EventType#ERROR} 和 {@link ForestEvent.EventType#PAUSE}
 * 事件并记录 WARN 级别日志，不影响事件继续向下传递。
 */
public class ErrorLogSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogSink.class);

    private final EventSink next;

    public ErrorLogSink(EventSink next) {
        this.next = next;
    }

    @Override
    public void emit(ForestEvent event) {
        if (event.type() == ForestEvent.EventType.ERROR) {
            log.warn("[ForestError] nodeId={} message={}", event.nodeId(), event.message());
        } else if (event.type() == ForestEvent.EventType.PAUSE) {
            log.warn("[ForestPause] nodeId={} reason={}", event.nodeId(), event.message());
        }
        next.emit(event);
    }
}
