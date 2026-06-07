package com.icusu.sivan.domain.conversation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储接口。
 */
public interface IMessageRepository {

    /** 根据 ID 查找消息。 */
    Optional<Message> findById(UUID messageId);

    /** 批量根据 ID 查找消息。 */
    List<Message> findByIds(Collection<UUID> messageIds);

    /** 根据对话 ID 查找消息列表。 */
    List<Message> findByConversationId(UUID conversationId);

    /** 保存消息。 */
    Message save(Message message);

    /** 删除消息。 */
    void delete(UUID messageId);

    /** 删除指定对话的所有消息。 */
    void deleteByConversationId(UUID conversationId);

    /** 分页查询：获取最旧的消息（按 sortOrder 正序，用于初始加载最新消息时反转） */
    List<Message> findLatestByConversationId(UUID conversationId, int limit);

    /** 分页查询：获取比 beforeSortOrder 更旧的消息 */
    List<Message> findBeforeSortOrder(UUID conversationId, int beforeSortOrder, int limit);

    /** 会话消息总数 */
    int countByConversationId(UUID conversationId);

    /** 统计比指定 sortOrder 更旧的消息数（用于游标分页 hasMore 判断） */
    int countBeforeSortOrder(UUID conversationId, int beforeSortOrder);

    /** 获取会话中最新的一条用户消息 */
    Optional<Message> findLatestUserMessage(UUID conversationId);

    /** 根据生成组 ID 查找消息列表。 */
    List<Message> findByGenerationGroup(UUID generationGroup);

    /** 统计指定生成组中的消息数量 */
    int countByGenerationGroup(UUID generationGroup);
}
