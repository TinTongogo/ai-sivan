package com.icusu.sivan.domain.tool;

import java.util.List;
import java.util.UUID;

/**
 * 工具使用记录仓库接口。
 */
public interface IToolUsageRepository {

    void save(ToolUsage toolUsage);

    /** 按工具名统计使用次数，降序排列。 */
    List<Object[]> countByToolName(UUID accountId);

    /** 按智能体和工具名统计使用次数，降序排列。 */
    List<Object[]> countByToolNameAndAgent(UUID accountId, String agentName);
}
