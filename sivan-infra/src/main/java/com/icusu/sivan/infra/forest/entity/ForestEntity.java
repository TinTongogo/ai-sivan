package com.icusu.sivan.infra.forest.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * forests 表 JPA 实体 — 森林聚合根。
 */
@Entity
@Table(name = "forests")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForestEntity extends BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "forest_id")
    private UUID forestId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(name = "root_node_id", nullable = false)
    private String rootNodeId;

    @Override
    public UUID getId() {
        return forestId;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
