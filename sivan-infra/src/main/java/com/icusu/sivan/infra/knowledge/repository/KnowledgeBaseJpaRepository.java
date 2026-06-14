package com.icusu.sivan.infra.knowledge.repository;

import com.icusu.sivan.infra.knowledge.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库表数据访问接口。
 */
@Repository
public interface KnowledgeBaseJpaRepository extends JpaRepository<KnowledgeBaseEntity, String> {

    Optional<KnowledgeBaseEntity> findByKbNameAndAccountId(String kbName, UUID accountId);

    List<KnowledgeBaseEntity> findByAccountId(UUID accountId);

    void deleteByKbNameAndAccountId(String kbName, UUID accountId);
}
