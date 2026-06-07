package com.icusu.sivan.domain.shared.vo;

import java.util.UUID;

/**
 * 对话标识值对象。
 */
public record ConversationId(UUID value) {

    public static ConversationId generate() {
        return new ConversationId(UUID.randomUUID());
    }

    public static ConversationId fromString(String s) {
        return new ConversationId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
