package com.icusu.sivan.domain.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件标记接口。所有领域事件实现此接口，通过 Spring ApplicationEventPublisher 发布。
 */
public interface DomainEvent {

    /** 事件唯一标识。 */
    default UUID eventId() {
        return UUID.randomUUID();
    }

    /** 事件发生时间。 */
    default Instant occurredAt() {
        return Instant.now();
    }

    /** 事件所属账号（用于多用户路由）。 */
    UUID accountId();
}
