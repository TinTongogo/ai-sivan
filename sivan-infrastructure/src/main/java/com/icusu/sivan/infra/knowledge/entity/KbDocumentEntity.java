package com.icusu.sivan.infra.knowledge.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * kb_documents 表 JPA 实体，表示知识库中的文档。
 */
@Entity
@Table(name = "kb_documents")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KbDocumentEntity extends BaseCreateOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID docId;

    @Column(name = "kb_name", nullable = false, length = 128)
    private String kbName;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 256)
    private String filename;

    @Column(name = "source_path", length = 512)
    private String sourcePath;

    @Column(name = "file_type", nullable = false, length = 16)
    @Builder.Default
    private String fileType = "";

    @Column(name = "char_count")
    @Builder.Default
    private Integer charCount = 0;

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;
}
