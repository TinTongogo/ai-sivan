package com.icusu.sivan.infra.orchestration.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * contracts 表 JPA 实体，表示 Squad 执行中各智能体间的契约数据。
 */
@Entity
@Table(name = "contracts", indexes = {
        @Index(name = "idx_contract_execution", columnList = "execution_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID contractId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false)
    private Integer phase;

    @Column(name = "source_agent", length = 64)
    private String sourceAgent;

    @Column(name = "target_agent", length = 64)
    private String targetAgent;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_type", length = 32)
    @Builder.Default
    private String contentType = "text";
}
