package com.icusu.sivan.common.util;

import com.icusu.sivan.common.exception.ResourceNotFoundException;

import java.util.UUID;
import java.util.Optional;
import java.util.function.Function;

/**
 * 所有权校验工具。统一各 Service 中重复的 findOwned 模式。
 */
public final class OwnershipValidator {

    private OwnershipValidator() {}

    /**
     * 查找实体并校验所有权。未找到或不属于当前用户均抛出 ResourceNotFoundException。
     *
     * @param <T>          实体类型
     * @param entityName   实体中文名称（用于错误消息）
     * @param entityId     实体 ID
     * @param finder       查找函数（按 ID 返回 Optional）
     * @param accountGetter 获取实体所属 accountId 的函数
     * @return 校验通过的实体
     */
    public static <T> T findOwned(UUID accountId, String entityName, UUID entityId,
                                   Function<UUID, Optional<T>> finder,
                                   Function<T, UUID> accountGetter) {
        T entity = finder.apply(entityId)
                .orElseThrow(() -> ResourceNotFoundException.notFound(entityName, entityId));
        if (!accountGetter.apply(entity).equals(accountId)) {
            throw ResourceNotFoundException.notFound(entityName, entityId);
        }
        return entity;
    }
}
