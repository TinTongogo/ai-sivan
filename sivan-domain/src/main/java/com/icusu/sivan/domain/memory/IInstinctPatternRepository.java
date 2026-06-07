package com.icusu.sivan.domain.memory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本能模板仓储接口。
 */
public interface IInstinctPatternRepository {

    /** 根据 ID 查找本能模板。 */
    Optional<InstinctPattern> findById(UUID patternId);

    /** 获取指定用户的活跃本能模板。 */
    List<InstinctPattern> findActiveByAccount(UUID accountId);

    /** 获取指定用户指定时间后创建的本能模板（防抖用）。 */
    List<InstinctPattern> findByAccountIdAndCreatedAtAfter(UUID accountId, LocalDateTime after);

    /** 获取全部账号的活跃本能模板（维护任务用）。 */
    List<InstinctPattern> findAllActive();

    /** 保存本能模板。 */
    void save(InstinctPattern pattern);

    /** 更新本能模板。 */
    void update(InstinctPattern pattern);

    /** 删除本能模板。 */
    void delete(UUID patternId);
}
