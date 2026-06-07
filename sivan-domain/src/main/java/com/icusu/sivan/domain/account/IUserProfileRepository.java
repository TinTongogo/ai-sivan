package com.icusu.sivan.domain.account;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户画像仓储接口。
 */
public interface IUserProfileRepository {

    /** 根据账户 ID 查找启用的画像。 */
    Optional<UserProfile> findByAccountId(UUID accountId);

    /** 保存画像（新建或更新）。 */
    UserProfile save(UserProfile profile);

    /** 删除画像。 */
    void delete(UUID profileId);
}
