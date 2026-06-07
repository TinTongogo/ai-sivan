package com.icusu.sivan.domain.shared.vo;

import java.util.UUID;

/**
 * 智能体标识值对象。类型安全防止 UUID 混用。
 */
public record AgentId(UUID value) {

    public static AgentId generate() {
        return new AgentId(UUID.randomUUID());
    }

    public static AgentId fromString(String s) {
        return new AgentId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
