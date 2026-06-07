package com.icusu.sivan.infra.knowledge.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * kb_vectors 表 JPA 实体，存储文档块的向量和元数据。
 * vector 字段使用 FloatArrayVectorType + @ColumnTransformer 以 text→vector 显式转换写入。
 */
@Entity
@Table(name = "kb_vectors")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KbVectorEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_name", nullable = false, length = 128)
    private String kbName;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "content_type", length = 20)
    @Builder.Default
    private String contentType = "text";

    @Column(name = "image_path", length = 512)
    private String imagePath;

    @Column(nullable = false, columnDefinition = "vector(1024)")
    @ColumnTransformer(write = "?::vector")
    @Type(FloatArrayVectorType.class)
    private float[] vector;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    @Builder.Default
    private String metadata = "{}";

    @Column(name = "content_hash", length = 32)
    private String contentHash;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;
}
