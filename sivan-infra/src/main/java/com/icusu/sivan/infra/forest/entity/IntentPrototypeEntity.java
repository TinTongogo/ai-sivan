package com.icusu.sivan.infra.forest.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 意图原型配置 — 用户可自定义的闲聊/任务原型文本。
 */
@Entity
@Table(name = "intent_prototypes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentPrototypeEntity {

    @Id
    @Column(name = "prototype_key", length = 32)
    private String prototypeKey;

    @Column(name = "prototype_text", nullable = false)
    private String prototypeText;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
