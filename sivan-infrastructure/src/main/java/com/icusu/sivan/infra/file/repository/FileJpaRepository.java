package com.icusu.sivan.infra.file.repository;

import com.icusu.sivan.infra.file.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 文件表数据访问接口。
 */
public interface FileJpaRepository extends JpaRepository<FileEntity, UUID> {
}
