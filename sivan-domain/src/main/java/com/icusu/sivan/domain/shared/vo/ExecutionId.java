package com.icusu.sivan.domain.shared.vo;

import java.util.UUID;

/**
 * ExecutionId 值对象。类型安全防止 UUID 混用。
 */
public record ExecutionId(UUID value) {

    public static ExecutionId generate() {
        return new ExecutionId(UUID.randomUUID());
    }

    public static ExecutionId fromString(String s) {
        return new ExecutionId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
