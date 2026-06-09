package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;

/**
 * 无操作终端 Sink — SUMMARY 模式下所有事件到此为止。
 */
public class NoopSink implements EventSink {
    @Override
    public void emit(ForestEvent event) {
        // 静默
    }
}
