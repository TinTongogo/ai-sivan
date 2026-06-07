package com.icusu.sivan.infra.file.entity;

import com.icusu.sivan.infra.shared.entity.BaseCreateOnlyEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * files 表 JPA 实体，表示上传的文件记录。
 */
@Entity
@Table(name = "files", indexes = {
        @Index(name = "idx_files_account", columnList = "account_id")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileEntity extends BaseCreateOnlyEntity {

    @Id
    private UUID fileId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 127)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;
}
