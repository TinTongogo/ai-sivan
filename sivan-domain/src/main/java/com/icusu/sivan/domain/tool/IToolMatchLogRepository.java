package com.icusu.sivan.domain.tool;

import java.util.List;

/**
 * 工具语义匹配记录仓储接口。
 */
public interface IToolMatchLogRepository {

    void save(ToolMatchLog log);

    void saveAll(List<ToolMatchLog> logs);
}
