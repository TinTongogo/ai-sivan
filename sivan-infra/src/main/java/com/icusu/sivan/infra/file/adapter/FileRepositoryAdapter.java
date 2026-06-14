package com.icusu.sivan.infra.file.adapter;

import com.icusu.sivan.domain.file.FileRecord;
import com.icusu.sivan.domain.file.IFileRepository;
import com.icusu.sivan.infra.file.entity.FileEntity;
import com.icusu.sivan.infra.file.repository.FileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件记录仓储适配器，实现 IFileRepository。
 */
@Component
@RequiredArgsConstructor
public class FileRepositoryAdapter implements IFileRepository {

    private final FileJpaRepository jpaRepository;

    /**
     * 保存文件记录。
     */
    @Override
    public FileRecord save(FileRecord record) {
        FileEntity entity = toEntity(record);
        jpaRepository.save(entity);
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(entity.getCreatedAt() != null
                    ? entity.getCreatedAt().toLocalDateTime() : null);
        }
        return record;
    }

    /**
     * 根据 ID 查询文件记录。
     */
    @Override
    public Optional<FileRecord> findById(UUID fileId) {
        return jpaRepository.findById(fileId).map(this::toDomain);
    }

    /**
     * 根据 ID 删除文件记录。
     */
    @Override
    public void deleteById(UUID fileId) {
        jpaRepository.deleteById(fileId);
    }

    /**
     * 将实体转换为领域对象。
     */
    private FileRecord toDomain(FileEntity entity) {
        return FileRecord.builder()
                .fileId(entity.getFileId())
                .accountId(entity.getAccountId())
                .fileName(entity.getFileName())
                .mimeType(entity.getMimeType())
                .fileSize(entity.getFileSize())
                .storagePath(entity.getStoragePath())
                .createdAt(entity.getCreatedAt() != null
                        ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * 将领域对象转换为实体。
     */
    private FileEntity toEntity(FileRecord record) {
        FileEntity entity = new FileEntity();
        entity.setFileId(record.getFileId());
        entity.setAccountId(record.getAccountId());
        entity.setFileName(record.getFileName());
        entity.setMimeType(record.getMimeType());
        entity.setFileSize(record.getFileSize());
        entity.setStoragePath(record.getStoragePath());
        return entity;
    }
}
