package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;

/**
 * SUMMARY 模式装饰器 — 过滤掉 DETAIL 级别事件。
 */
public class SummarySink implements EventSink {

    private final EventSink wrapped;

    public SummarySink(EventSink wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emit(ForestEvent event) {
        if (event.type() == ForestEvent.EventType.DETAIL) {
            return;
        }
        wrapped.emit(event);
    }
}
