package com.icusu.sivan.domain.file;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件记录仓储接口。
 */
public interface IFileRepository {

    /** 保存文件记录。 */
    FileRecord save(FileRecord record);

    /** 根据 ID 查找文件记录。 */
    Optional<FileRecord> findById(UUID fileId);

    /** 根据 ID 删除文件记录。 */
    void deleteById(UUID fileId);
}
