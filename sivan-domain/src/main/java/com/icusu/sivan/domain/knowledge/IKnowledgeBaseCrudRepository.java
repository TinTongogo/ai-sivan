package com.icusu.sivan.domain.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库 CRUD 仓储接口。
 */
public interface IKnowledgeBaseCrudRepository {

    void save(KnowledgeBase kb);

    Optional<KnowledgeBase> findByNameAndAccount(String kbName, UUID accountId);

    List<KnowledgeBase> findAllByAccount(UUID accountId);

    void deleteByNameAndAccount(String kbName, UUID accountId);
}
