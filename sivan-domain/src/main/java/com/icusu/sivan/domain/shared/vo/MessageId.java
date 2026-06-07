package com.icusu.sivan.domain.shared.vo;

import java.util.UUID;

/**
 * MessageId 值对象。类型安全防止 UUID 混用。
 */
public record MessageId(UUID value) {

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    public static MessageId fromString(String s) {
        return new MessageId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
