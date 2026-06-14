package com.icusu.sivan.infra.account.entity;

import com.icusu.sivan.infra.shared.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * accounts 表 JPA 实体，表示用户账户。
 */
@Entity
@Table(name = "accounts")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID accountId;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(length = 128, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    private String quota;

    @JdbcTypeCode(SqlTypes.JSON)
    private String preferences;

    @Column(length = 16)
    @Builder.Default
    private String status = "active";

    @Column(name = "short_id", length = 32)
    private String shortId;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;
}
