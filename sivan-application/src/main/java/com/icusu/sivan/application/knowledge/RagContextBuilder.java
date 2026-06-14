package com.icusu.sivan.application.knowledge;

import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.shared.port.RagRetrievalPort;
import com.icusu.sivan.infra.knowledge.RerankerService;
import com.icusu.sivan.application.knowledge.dto.SearchRequest;
import com.icusu.sivan.application.knowledge.dto.SearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RAG 上下文构建器。根据对话绑定的知识库搜索相关内容，经 Reranker 重排序后格式化返回。
 * 未绑定知识库、搜索无结果或服务异常时返回 null，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContextBuilder implements RagRetrievalPort {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RerankerService rerankerService;
    private static final int DEFAULT_TOP_K = 5;

    @Override
    public String retrieveContext(String query, List<String> knowledgeBaseIds, UUID accountId) {
        if (query == null || query.isBlank()) return null;
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) return null;
        return searchAndFormat(query, knowledgeBaseIds, accountId);
    }

    /**
     * 构建 RAG 上下文纯文本。
     *
     * @param query        搜索查询文本
     * @param conversation 当前对话（提取 knowledgeBaseIds）
     * @param accountId    当前用户 ID
     * @return 格式化后的参考知识文本，无结果时返回 null
     */
    public String build(String query, Conversation conversation, UUID accountId) {
        return retrieveContext(query, conversation.getKnowledgeBaseIds(), accountId);
    }

    private String searchAndFormat(String query, List<String> kbIds, UUID accountId) {
        List<SearchResultResponse> allResults = new ArrayList<>();
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.setQuery(query);
        searchRequest.setTopK(DEFAULT_TOP_K);

        for (String kbName : kbIds) {
            try {
                List<SearchResultResponse> results = knowledgeBaseService.search(accountId, kbName, searchRequest);
                allResults.addAll(results);
            } catch (Exception e) {
                log.warn("知识库搜索失败: kbName={}, {}", kbName, e.getMessage());
            }
        }

        if (allResults.isEmpty()) return null;

        try {
            List<String> texts = allResults.stream().map(SearchResultResponse::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(query, texts);
            allResults = rerankedIndices.stream().limit(DEFAULT_TOP_K).map(allResults::get).toList();
        } catch (Exception e) {
            log.warn("Reranker 重排序失败: {}", e.getMessage());
            if (allResults.size() > DEFAULT_TOP_K) allResults = allResults.subList(0, DEFAULT_TOP_K);
        }

        return formatResults(allResults);
    }

    private static String formatResults(List<SearchResultResponse> results) {
        StringBuilder sb = new StringBuilder("[参考知识库内容]\n");
        for (SearchResultResponse result : results) {
            sb.append("来自 [").append(result.getKbName()).append("]：\n");
            sb.append("  - ").append(result.getText()).append("\n");
        }
        return sb.toString();
    }
}
