package com.icusu.sivan.domain.knowledge;

import java.util.List;
import java.util.UUID;

/**
 * 知识库搜索端口 — 领域层定义，基础设施/web 层实现。
 * 使 SearchKBLeafExecutor（sivan-agent）能不依赖 sivan-web 模块调用知识库搜索。
 */
public interface KbSearchPort {

    /**
     * 在指定知识库中搜索。
     *
     * @param query   搜索关键词
     * @param kbName  目标知识库名称，null 表示搜索全部
     * @param topK    返回结果数
     * @param accountId 账户 ID
     * @return 搜索结果列表，每个元素是 "score||content" 格式的字符串
     */
    List<String> search(String query, String kbName, int topK, UUID accountId);
}
