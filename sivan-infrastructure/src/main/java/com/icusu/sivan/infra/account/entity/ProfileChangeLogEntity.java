package com.icusu.sivan.infra.account.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * profile_changelog 表 JPA 实体。
 */
@Entity
@Table(name = "profile_changelog")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileChangeLogEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID logId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(length = 32)
    private String source;

    @Column(name = "field_name", length = 64)
    private String fieldName;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;
}
