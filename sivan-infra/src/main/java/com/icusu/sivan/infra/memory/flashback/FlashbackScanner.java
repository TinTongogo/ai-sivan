package com.icusu.sivan.infra.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.node.MemoryNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.memory.MemoryRetrievalStrategy;
import com.icusu.sivan.domain.memory.curve.EbbinghausForgettingCurve;
import com.icusu.sivan.domain.memory.flashback.FlashbackCandidate;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 情境闪现扫描器。
 * 在低保留率的记忆中寻找高相关度的条目，主动注入到当前对话上下文中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlashbackScanner implements MemoryRetrievalStrategy<FlashbackCandidate> {

    private final ForestRepository forestRepository;
    private final IEmbeddingService embeddingService;

    private static final int MAX_CANDIDATES = 10;
    private static final double ACTIVATION_THRESHOLD = 0.7;
    private static final double IMPORTANCE_WEIGHT = 2.5;

    private static final Map<MemoryLevel, Double> LEVEL_WEIGHTS = Map.of(
            MemoryLevel.SESSION, 0.3,
            MemoryLevel.PROJECT, 0.8,
            MemoryLevel.USER,    1.0
    );

    public List<FlashbackCandidate> scan(UUID accountId, String context, int limit) {
        List<? extends TreeNode> allNodes;
        if (context != null && !context.isBlank() && embeddingService.isAvailable()) {
            allNodes = vectorSearch(accountId, context, MAX_CANDIDATES * 2);
        } else {
            allNodes = forestRepository.findNodesByTypeAndAccount(accountId, "memory", MAX_CANDIDATES * 2);
        }
        if (allNodes == null) allNodes = Collections.emptyList();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int take = Math.min(Math.max(limit, 1), MAX_CANDIDATES);

        List<FlashbackCandidate> candidates = allNodes.stream()
                .filter(n -> n instanceof MemoryNode)
                .map(n -> (MemoryNode) n)
                .filter(m -> m.metadata().get("archived") == null || !Boolean.TRUE.equals(m.metadata().get("archived")))
                .map(m -> toCandidate(m, now, context))
                .filter(c -> c.getRelevanceScore() > 0)
                .sorted(Comparator.comparingDouble(FlashbackCandidate::getRelevanceScore).reversed())
                .limit(take)
                .toList();

        if (!candidates.isEmpty()) {
            log.debug("情境闪现: {} 候选, 最高相关度={}", candidates.size(),
                    candidates.getFirst().getRelevanceScore());
        }

        return candidates;
    }

    @Override
    public List<FlashbackCandidate> retrieve(String query, UUID accountId, int limit) {
        return scan(accountId, query, limit);
    }

    private List<? extends TreeNode> vectorSearch(UUID accountId, String context, int topK) {
        float[] queryVec = embeddingService.embed(context);
        if (queryVec == null) {
            return forestRepository.findNodesByTypeAndAccount(accountId, "memory", topK);
        }
        return forestRepository.semanticSearchMemory(accountId, queryVec, topK, null);
    }

    public List<FlashbackCandidate> quickScan(UUID accountId) {
        return scan(accountId, "", 5);
    }

    private FlashbackCandidate toCandidate(MemoryNode node, LocalDateTime now, String context) {
        MetadataAccess meta = new MetadataAccess(node.metadata());
        int accessCount = meta.accessCount();
        MemoryLevel level = meta.level();
        java.time.LocalDateTime lastAccessedAt = meta.lastAccessedAt();

        double retention = EbbinghausForgettingCurve.calculateRetentionWithAccess(
                level, lastAccessedAt, Math.max(accessCount, 1), now);

        if (retention > ACTIVATION_THRESHOLD) {
            return FlashbackCandidate.builder().relevanceScore(0).build();
        }

        double score = computeRelevanceScore(node, level, retention, accessCount, context);

        return FlashbackCandidate.builder()
                .memoryId(java.util.UUID.fromString(node.nodeId()))
                .content(node.content())
                .level(level)
                .relevanceScore(score)
                .retention(retention)
                .accessCount(accessCount)
                .important(meta.important())
                .lastAccessedAt(lastAccessedAt)
                .build();
    }

    private double computeRelevanceScore(MemoryNode node, MemoryLevel level, double retention,
                                          int accessCount, String context) {
        MetadataAccess meta = new MetadataAccess(node.metadata());
        double importanceWeight = meta.important() ? IMPORTANCE_WEIGHT : 1.0;
        double levelWeight = LEVEL_WEIGHTS.getOrDefault(level, 0.5);
        double forgettingFactor = 1.0 - Math.max(retention, 0);
        double frequencyFactor = Math.log(accessCount + 1) + 0.5;

        double contextBonus = 0.0;
        if (context != null && !context.isBlank() && node.content() != null) {
            String ctx = context.toLowerCase();
            String content = node.content().toLowerCase();
            String[] keywords = ctx.split("[\\s,，。.、！!?？]+");
            long matchCount = Arrays.stream(keywords)
                    .filter(kw -> kw.length() > 1 && content.contains(kw))
                    .count();
            contextBonus = matchCount * 0.2;
        }

        return importanceWeight * levelWeight * forgettingFactor * frequencyFactor + contextBonus;
    }

    /** 从 MemoryNode metadata 中提取字段的辅助类。 */
    private static class MetadataAccess {
        private final Map<String, Object> meta;
        MetadataAccess(Map<String, Object> meta) { this.meta = meta; }
        int accessCount() { return meta.containsKey("accessCount") ? ((Number)meta.get("accessCount")).intValue() : 0; }
        MemoryLevel level() {
            String lv = (String) meta.get("level");
            return lv != null ? MemoryLevel.valueOf(lv) : MemoryLevel.PROJECT;
        }
        java.time.LocalDateTime lastAccessedAt() {
            Object v = meta.get("lastAccessedAt");
            if (v instanceof String s) return java.time.LocalDateTime.parse(s);
            return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
        boolean important() { return Boolean.TRUE.equals(meta.get("important")); }
    }
}
