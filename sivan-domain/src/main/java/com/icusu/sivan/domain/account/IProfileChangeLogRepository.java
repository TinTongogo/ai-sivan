package com.icusu.sivan.domain.account;

import java.util.List;
import java.util.UUID;

/**
 * 画像变更日志仓储接口。
 */
public interface IProfileChangeLogRepository {

    /** 保存变更记录。 */
    void save(ProfileChangeLog log);

    /** 查询指定用户最近的 N 条变更记录。 */
    List<ProfileChangeLog> findByAccountId(UUID accountId, int limit);
}
