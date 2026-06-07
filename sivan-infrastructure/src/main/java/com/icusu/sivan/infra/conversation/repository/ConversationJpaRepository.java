package com.icusu.sivan.infra.conversation.repository;

import com.icusu.sivan.infra.conversation.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 会话表数据访问接口。
 */
@Repository
public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<ConversationEntity> findByAccountIdAndProjectIdOrderByCreatedAtDesc(UUID accountId, UUID projectId);

    long countByAccountId(UUID accountId);
}
