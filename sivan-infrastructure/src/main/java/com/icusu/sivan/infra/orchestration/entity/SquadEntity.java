package com.icusu.sivan.infra.orchestration.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * squads 表 JPA 实体，表示 Squad（智能体编队）配置。
 */
@Entity
@Table(name = "squads")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SquadEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID squadId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 16)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topology_json", columnDefinition = "JSONB")
    private String topologyJson;

    @Column(name = "usage_count")
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "success_rate")
    private Double successRate;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(length = 16)
    @Builder.Default
    private String source = "USER";

    @Column(name = "source_pattern_id")
    private UUID sourcePatternId;
}
