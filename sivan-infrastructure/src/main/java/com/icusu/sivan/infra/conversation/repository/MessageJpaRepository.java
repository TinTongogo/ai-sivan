package com.icusu.sivan.infra.conversation.repository;

import com.icusu.sivan.infra.conversation.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息表数据访问接口。
 */
@Repository
public interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByConversationIdOrderBySortOrderAsc(UUID conversationId);

    void deleteByConversationId(UUID conversationId);

    @Query("SELECT COALESCE(MAX(m.sortOrder), 0) FROM MessageEntity m WHERE m.conversationId = :conversationId")
    Optional<Integer> findMaxSortOrderByConversationId(@Param("conversationId") UUID conversationId);

    Page<MessageEntity> findByConversationIdOrderBySortOrderDesc(UUID conversationId, Pageable pageable);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :conversationId AND m.sortOrder < :beforeSortOrder ORDER BY m.sortOrder DESC")
    List<MessageEntity> findBeforeSortOrder(@Param("conversationId") UUID conversationId,
                                            @Param("beforeSortOrder") int beforeSortOrder,
                                            Pageable pageable);

    int countByConversationId(UUID conversationId);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :conversationId AND m.sortOrder < :beforeSortOrder")
    int countBeforeSortOrder(@Param("conversationId") UUID conversationId,
                             @Param("beforeSortOrder") int beforeSortOrder);

    Optional<MessageEntity> findFirstByConversationIdAndRoleOrderBySortOrderDesc(UUID conversationId, String role);

    List<MessageEntity> findByGenerationGroupOrderByGenerationIndexAsc(UUID generationGroup);

    int countByGenerationGroup(UUID generationGroup);

    /** 全文搜索消息内容（按账号）。 */
    @Query(value = "SELECT * FROM messages m " +
           "JOIN conversations c ON m.conversation_id = c.conversation_id " +
           "WHERE c.account_id = :accountId " +
           "AND m.content ILIKE '%' || :keyword || '%' " +
           "ORDER BY m.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<MessageEntity> searchByContent(@Param("accountId") UUID accountId,
                                        @Param("keyword") String keyword,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);
}
