package com.icusu.sivan.infra.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryRepository;
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

    private final IMemoryRepository memoryRepository;
    private final IEmbeddingService embeddingService;

    private static final int MAX_CANDIDATES = 10;
    private static final double ACTIVATION_THRESHOLD = 0.7;
    private static final double IMPORTANCE_WEIGHT = 2.5;

    private static final Map<MemoryLevel, Double> LEVEL_WEIGHTS = Map.of(
            MemoryLevel.SESSION, 0.3,
            MemoryLevel.USER,    1.0,
            MemoryLevel.TEAM,    0.7,
            MemoryLevel.PROJECT, 0.8
    );

    public List<FlashbackCandidate> scan(UUID accountId, String context, int limit) {
        List<MemoryEntry> allMemories;
        if (context != null && !context.isBlank() && embeddingService.isAvailable()) {
            allMemories = vectorSearch(accountId, context, MAX_CANDIDATES * 2);
        } else {
            allMemories = memoryRepository.findAllByAccount(accountId);
        }
        if (allMemories == null) allMemories = Collections.emptyList();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int take = Math.min(Math.max(limit, 1), MAX_CANDIDATES);

        List<FlashbackCandidate> candidates = allMemories.stream()
                .filter(m -> m.getLevel() != null && m.getLastAccessedAt() != null)
                .filter(m -> !Boolean.TRUE.equals(m.getArchived()))
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

    private List<MemoryEntry> vectorSearch(UUID accountId, String context, int topK) {
        float[] queryVec = embeddingService.embed(context);
        if (queryVec == null) {
            return memoryRepository.findAllByAccount(accountId);
        }
        Set<UUID> seen = new HashSet<>();
        List<MemoryEntry> results = new ArrayList<>();
        for (MemoryLevel level : MemoryLevel.values()) {
            List<MemoryEntry> hits = memoryRepository.semanticSearch(
                    accountId, level, "", queryVec, topK);
            for (MemoryEntry hit : hits) {
                if (hit != null && seen.add(hit.getMemoryId())) {
                    results.add(hit);
                }
            }
        }
        return results;
    }

    public List<FlashbackCandidate> quickScan(UUID accountId) {
        return scan(accountId, "", 5);
    }

    private FlashbackCandidate toCandidate(MemoryEntry entry, LocalDateTime now, String context) {
        int accessCount = entry.getAccessCount() != null ? entry.getAccessCount() : 0;
        double retention = EbbinghausForgettingCurve.calculateRetentionWithAccess(
                entry.getLevel(), entry.getLastAccessedAt(), Math.max(accessCount, 1), now);

        if (retention > ACTIVATION_THRESHOLD) {
            return FlashbackCandidate.builder().relevanceScore(0).build();
        }

        double score = computeRelevanceScore(entry, retention, accessCount, context);

        return FlashbackCandidate.builder()
                .memoryId(entry.getMemoryId())
                .content(entry.getContent())
                .level(entry.getLevel())
                .relevanceScore(score)
                .retention(retention)
                .accessCount(accessCount)
                .important(Boolean.TRUE.equals(entry.getImportant()))
                .lastAccessedAt(entry.getLastAccessedAt())
                .build();
    }

    private double computeRelevanceScore(MemoryEntry entry, double retention,
                                          int accessCount, String context) {
        double importanceWeight = Boolean.TRUE.equals(entry.getImportant())
                ? IMPORTANCE_WEIGHT : 1.0;
        double levelWeight = LEVEL_WEIGHTS.getOrDefault(entry.getLevel(), 0.5);
        double forgettingFactor = 1.0 - Math.max(retention, 0);
        double frequencyFactor = Math.log(accessCount + 1) + 0.5;

        double contextBonus = 0.0;
        if (context != null && !context.isBlank() && entry.getContent() != null) {
            String ctx = context.toLowerCase();
            String content = entry.getContent().toLowerCase();
            String[] keywords = ctx.split("[\\s,，。.、！!?？]+");
            long matchCount = Arrays.stream(keywords)
                    .filter(kw -> kw.length() > 1 && content.contains(kw))
                    .count();
            contextBonus = matchCount * 0.2;
        }

        return importanceWeight * levelWeight * forgettingFactor * frequencyFactor + contextBonus;
    }
}
