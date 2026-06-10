package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.infra.forest.entity.ForestExecutionLogEntity;
import com.icusu.sivan.infra.forest.repository.ForestExecutionLogJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 执行日志 Sink — 将 Forest 生命周期事件写入 {@code forest_execution_logs} 表。
 * <p>
 * 仅记录 LIFECYCLE 和 ERROR 事件，DETAIL/THINKING 等高频事件跳过。
 */
public class ExecutionLogSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogSink.class);

    private final EventSink wrapped;
    private final ForestExecutionLogJpaRepository repository;

    public ExecutionLogSink(EventSink wrapped, ForestExecutionLogJpaRepository repository) {
        this.wrapped = wrapped;
        this.repository = repository;
    }

    @Override
    public void emit(ForestEvent event) {
        wrapped.emit(event);

        if ((event.type() == ForestEvent.EventType.LIFECYCLE
                || event.type() == ForestEvent.EventType.ERROR
                || event.type() == ForestEvent.EventType.MILESTONE)
                && event.forestId() != null) {
            try {
                repository.save(ForestExecutionLogEntity.builder()
                        .nodeId(event.nodeId())
                        .forestId(UUID.fromString(event.forestId()))
                        .eventType(event.type().name())
                        .message(event.message())
                        .build());
            } catch (Exception e) {
                log.debug("写入执行日志失败（不影响执行）: {}", e.getMessage());
            }
        }
    }

}
