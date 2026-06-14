package com.icusu.sivan.infra.memory.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * instinct_patterns 表 JPA 实体，表示智能体本能模式（自动路由任务拓扑）。
 */
@Entity
@Table(name = "instinct_patterns")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstinctPatternEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID patternId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topology_json", columnDefinition = "JSONB")
    private String topologyJson;

    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    @Column(name = "total_count")
    @Builder.Default
    private Integer totalCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = false;

    // ===== 特征驱动字段 =====

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_vector", columnDefinition = "JSONB")
    private String featureVector;

    @Column(name = "execution_mode", length = 32)
    private String executionMode;

    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    @Column(name = "source_pattern_id")
    private UUID sourcePatternId;

    @Column(name = "last_match_at")
    private OffsetDateTime lastMatchAt;

    @Column(name = "success_rate")
    private Double successRate;

    @Column(name = "weight")
    @Builder.Default
    private Double weight = 0.5;

    @Column(name = "draft")
    @Builder.Default
    private Boolean draft = false;
}
