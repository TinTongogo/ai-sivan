package com.icusu.sivan.infra.account.entity;

import com.icusu.sivan.infra.knowledge.entity.FloatArrayVectorType;
import com.icusu.sivan.infra.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * user_profiles 表 JPA 实体。
 */
@Entity
@Table(name = "user_profiles")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID profileId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "ai_language", length = 10)
    @Builder.Default
    private String aiLanguage = "auto";

    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<String> expertise = List.of();

    @Builder.Default
    private boolean active = true;

    @Column(columnDefinition = "vector(2048)")
    @ColumnTransformer(write = "?::vector")
    @Type(FloatArrayVectorType.class)
    private float[] vector;

    @Column(name = "auto_learn")
    @Builder.Default
    private boolean autoLearn = true;
}
