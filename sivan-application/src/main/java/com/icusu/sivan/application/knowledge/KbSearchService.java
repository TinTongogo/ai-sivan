package com.icusu.sivan.application.knowledge;

import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.infra.knowledge.RerankerService;
import com.icusu.sivan.application.knowledge.dto.SearchRequest;
import com.icusu.sivan.application.knowledge.dto.SearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 知识库搜索服务 — 向量搜索、全文搜索、查询改写增强、Reranker 重排。
 * <p>
 * 从 {@link KnowledgeBaseService} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbSearchService {

    private final IKnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;
    private final QueryRewriter queryRewriter;

    /**
     * 搜索知识库。VECTOR 模式使用向量检索 + Reranker 重排，FULLTEXT 模式使用 pg_trgm。
     */
    public List<SearchResultResponse> search(UUID accountId, String kbName, SearchRequest request) {
        findOwned(accountId, kbName);

        if (request.getMode() == SearchRequest.SearchMode.FULLTEXT) {
            return knowledgeBaseRepository.fulltextSearch(accountId, request.getQuery(), request.getTopK())
                    .stream()
                    .map(this::toSearchResultResponse)
                    .toList();
        }

        if (request.isExpandQuery() && accountId != null) {
            return expandedVectorSearch(accountId, request.getQuery(), request.getTopK(),
                    (query, topK) -> knowledgeBaseRepository.search(kbName, accountId,
                            embeddingService.embed(query), topK));
        }

        return vectorSearchWithRerank(
                request.getQuery(), request.getTopK(),
                (topK) -> knowledgeBaseRepository.search(kbName, accountId,
                        embeddingService.embed(request.getQuery()), topK));
    }

    /**
     * 跨知识库搜索。VECTOR 模式使用向量检索 + Reranker 重排，FULLTEXT 模式使用 pg_trgm。
     */
    public List<SearchResultResponse> searchAll(UUID accountId, SearchRequest request) {
        if (request.getMode() == SearchRequest.SearchMode.FULLTEXT) {
            return knowledgeBaseRepository.fulltextSearch(accountId, request.getQuery(), request.getTopK())
                    .stream()
                    .map(this::toSearchResultResponse)
                    .toList();
        }

        if (request.isExpandQuery() && accountId != null) {
            return expandedVectorSearch(accountId, request.getQuery(), request.getTopK(),
                    (query, topK) -> knowledgeBaseRepository.searchAll(accountId,
                            embeddingService.embed(query), topK));
        }

        return vectorSearchWithRerank(
                request.getQuery(), request.getTopK(),
                (topK) -> knowledgeBaseRepository.searchAll(accountId,
                        embeddingService.embed(request.getQuery()), topK));
    }

    /**
     * 查询改写增强的向量搜索：将原始查询扩展为多个变体，分别向量搜索后合并去重。
     */
    private List<SearchResultResponse> expandedVectorSearch(UUID accountId, String query, int topK,
                                                             BiFunction<String, Integer, List<SearchResult>> searcher) {
        List<String> queries;
        try {
            queries = Mono.fromCallable(() ->
                            queryRewriter.rewrite(query, accountId).block(Duration.ofSeconds(5)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
            queries = List.of(query);
        }
        if (queries == null || queries.isEmpty()) queries = List.of(query);

        int fetchTopK = Math.min(topK * 2, 50);
        List<SearchResult> merged = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String q : queries) {
            try {
                List<SearchResult> results = searcher.apply(q, fetchTopK);
                if (results != null) {
                    for (SearchResult r : results) {
                        String text = r.getText();
                        if (text != null && seen.add(text)) {
                            merged.add(r);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("查询变体搜索失败: query='{}', {}", q.length() > 40 ? q.substring(0, 40) + "..." : q, e.getMessage());
            }
        }

        if (merged.isEmpty()) return Collections.emptyList();

        try {
            List<String> texts = merged.stream().map(SearchResult::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(query, texts);
            if (rerankedIndices != null && !rerankedIndices.isEmpty()) {
                return rerankedIndices.stream()
                        .filter(i -> i < merged.size())
                        .limit(topK)
                        .map(merged::get)
                        .map(this::toSearchResultResponse)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Reranker 重排序失败: {}", e.getMessage());
        }

        return merged.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .map(this::toSearchResultResponse)
                .toList();
    }

    /**
     * 向量搜索 + Reranker 重排序。
     */
    private List<SearchResultResponse> vectorSearchWithRerank(String query, int topK,
                                                               Function<Integer, List<SearchResult>> searcher) {
        List<SearchResult> results;
        try {
            int fetchTopK = Math.min(topK * 3, 100);
            results = searcher.apply(fetchTopK);
        } catch (Exception e) {
            log.warn("向量搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (results == null || results.isEmpty()) return Collections.emptyList();

        try {
            List<String> texts = results.stream().map(SearchResult::getText).toList();
            List<Integer> rerankedIndices = rerankerService.rerank(query, texts);
            if (rerankedIndices != null && !rerankedIndices.isEmpty()) {
                return rerankedIndices.stream()
                        .filter(i -> i < results.size())
                        .limit(topK)
                        .map(results::get)
                        .map(this::toSearchResultResponse)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Reranker 重排序失败，按原始得分排序: {}", e.getMessage());
        }
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .map(this::toSearchResultResponse)
                .toList();
    }

    private KnowledgeBase findOwned(UUID accountId, String kbName) {
        return knowledgeBaseRepository.findByNameAndAccount(kbName, accountId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("知识库", kbName));
    }

    private SearchResultResponse toSearchResultResponse(SearchResult result) {
        return SearchResultResponse.builder()
                .chunkId(result.getChunkId())
                .kbName(result.getKbName())
                .text(result.getText())
                .contentType(result.getContentType())
                .imagePath(result.getImagePath())
                .score(result.getScore())
                .metadata(result.getMetadata())
                .build();
    }
}
