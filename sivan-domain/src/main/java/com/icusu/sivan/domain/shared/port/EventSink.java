package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.ForestEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 事件输出通道 — Decorator 模式，支持多层装饰组合。
 * <p>
 * 纪律：业务逻辑代码中不出现 {@code if (delivery == STREAM)} 的判断。
 * 所有输出判断走 EventSink 装饰器链。
 */
@FunctionalInterface
public interface EventSink {

    /** 发射一个事件 */
    void emit(ForestEvent event);

    /** 装饰器辅助方法：前置过滤 */
    default EventSink filter(Predicate<ForestEvent> predicate) {
        EventSink self = this;
        return event -> { if (predicate.test(event)) self.emit(event); };
    }

    /** 装饰器辅助方法：后置转换 */
    default EventSink then(Consumer<ForestEvent> after) {
        EventSink self = this;
        return event -> { self.emit(event); after.accept(event); };
    }
}
