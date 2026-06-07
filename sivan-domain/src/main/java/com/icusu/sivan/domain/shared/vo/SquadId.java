package com.icusu.sivan.domain.shared.vo;

import java.util.UUID;

/**
 * SquadId 值对象。类型安全防止 UUID 混用。
 */
public record SquadId(UUID value) {

    public static SquadId generate() {
        return new SquadId(UUID.randomUUID());
    }

    public static SquadId fromString(String s) {
        return new SquadId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
