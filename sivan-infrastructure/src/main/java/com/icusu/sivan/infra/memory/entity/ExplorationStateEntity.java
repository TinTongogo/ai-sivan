package com.icusu.sivan.infra.memory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * exploration_state 表 JPA 实体。
 */
@Entity
@Table(name = "exploration_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExplorationStateEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "call_count", nullable = false)
    @Builder.Default
    private int callCount = 0;

    @Column(name = "last_exploration_call", nullable = false)
    @Builder.Default
    private int lastExplorationCall = -3;
}
