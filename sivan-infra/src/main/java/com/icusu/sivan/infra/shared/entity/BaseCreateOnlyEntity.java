package com.icusu.sivan.infra.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 实体基类 — 仅创建时间（无 updated_at）。
 * 用于只写不更新的记录（TokenUsage、Contract、Message 等）。
 */
@MappedSuperclass
@Getter
public abstract class BaseCreateOnlyEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 持久化前自动设置 UTC 创建时间。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
