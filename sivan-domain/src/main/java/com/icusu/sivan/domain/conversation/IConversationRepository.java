package com.icusu.sivan.domain.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话仓储接口。
 */
public interface IConversationRepository {

    /** 根据 ID 查找对话。 */
    Optional<Conversation> findById(UUID conversationId);

    /** 获取指定用户的所有对话。 */
    List<Conversation> findAllByAccount(UUID accountId);

    /** 获取指定用户和项目的对话列表。 */
    List<Conversation> findAllByAccountAndProject(UUID accountId, UUID projectId);

    /** 保存对话。 */
    void save(Conversation conversation);

    /** 更新对话。 */
    void update(Conversation conversation);

    /** 删除对话。 */
    void delete(UUID conversationId);

    /**
     * 统计指定用户下的对话总数。
     */
    long countByAccount(UUID accountId);
}
