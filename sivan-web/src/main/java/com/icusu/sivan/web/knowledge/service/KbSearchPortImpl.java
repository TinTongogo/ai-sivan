package com.icusu.sivan.web.knowledge.service;

import com.icusu.sivan.domain.knowledge.KbSearchPort;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.web.knowledge.dto.SearchRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@link KbSearchPort} 实现 — 委托给 {@link KnowledgeBaseService}。
 */
@Component
public class KbSearchPortImpl implements KbSearchPort {

    private final KnowledgeBaseService knowledgeBaseService;

    public KbSearchPortImpl(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public List<String> search(String query, String kbName, int topK, UUID accountId) {
        SearchRequest req = new SearchRequest();
        req.setQuery(query);
        req.setTopK(topK);
        req.setMode(SearchRequest.SearchMode.VECTOR);

        List<?> results;
        if (kbName != null && !kbName.isBlank()) {
            results = knowledgeBaseService.search(accountId, kbName, req);
        } else {
            results = knowledgeBaseService.searchAll(accountId, req);
        }

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.stream()
                .filter(r -> r instanceof SearchResult)
                .map(r -> {
                    SearchResult sr = (SearchResult) r;
                    return String.format("%.4f||%s", sr.getScore(), sr.getText());
                })
                .toList();
    }
}
