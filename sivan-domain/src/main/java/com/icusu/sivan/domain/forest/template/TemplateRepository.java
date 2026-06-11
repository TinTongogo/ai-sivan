package com.icusu.sivan.domain.forest.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模板仓储接口 — GoalTreeTemplate 的存储与检索。
 */
public interface TemplateRepository {

    /** 保存模板。 */
    void save(GoalTreeTemplate template);

    /** 按 ID 查找模板。 */
    Optional<GoalTreeTemplate> findById(UUID templateId);

    /** 查找账号下的所有模板。 */
    List<GoalTreeTemplate> findByAccountId(UUID accountId);

    /** 按名称模糊搜索模板。 */
    List<GoalTreeTemplate> searchByName(UUID accountId, String keyword);

    /** 删除模板。 */
    void delete(UUID templateId);

    /** 更新模板的 usage 统计。 */
    void updateStats(UUID templateId, int usageCount, int successCount);
}
