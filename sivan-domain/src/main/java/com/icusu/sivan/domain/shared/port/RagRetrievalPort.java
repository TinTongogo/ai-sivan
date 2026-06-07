package com.icusu.sivan.domain.shared.port;

/**
 * RAG 检索端口。领域层通过此接口获取知识库上下文。
 */
public interface RagRetrievalPort {

    /**
     * 根据查询和知识库 ID 列表检索相关上下文文本。
     * @param query 搜索查询
     * @param knowledgeBaseIds 知识库名称列表
     * @param accountId 当前用户 ID
     * @return 格式化后的参考文本，无结果时返回 null
     */
    String retrieveContext(String query, java.util.List<String> knowledgeBaseIds, java.util.UUID accountId);
}
