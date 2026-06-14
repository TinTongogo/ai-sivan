package com.icusu.sivan.application.conversation.tree;

import com.icusu.sivan.domain.context.ContextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Epoch 指纹缓存。追踪每个对话各 Epoch 的内容指纹，
 * 当指纹不变时将 Segment 标记为缓存命中（cacheBreakpoint=true）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>每个对话独立缓存</li>
 *   <li>指纹使用简单哈希（快速计算，不依赖外部服务）</li>
 *   <li>cacheBreakpoint=true 表示此段与上次一致，LLM 可复用缓存前缀</li>
 *   <li>cacheBreakpoint=false 表示内容更新，缓存前缀到此失效</li>
 * </ul>
 */
@Component
public class ContextCache {

    private static final Logger log = LoggerFactory.getLogger(ContextCache.class);

    /** conversationId → (epochIndex → fingerprint) */
    private final ConcurrentHashMap<UUID, Map<Integer, String>> cache = new ConcurrentHashMap<>();

    /**
     * 对构建结果应用缓存决策。
     * 逐 Epoch 比对指纹，命中时标记 cacheBreakpoint，否则更新指纹。
     */
    public ContextResult apply(UUID conversationId, ContextResult result) {
        if (conversationId == null || result == null || result.isEmpty()) {
            return result;
        }

        Map<Integer, String> convCache = cache.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>());
        List<ContextSegment> newSegments = new ArrayList<>();

        for (ContextSegment seg : result.getSegments()) {
            String content = seg.getContent();
            String fp = fingerprint(content);
            Integer epochIdx = seg.getEpoch().getIndex();
            String prevFp = convCache.get(epochIdx);

            boolean cacheHit = fp.equals(prevFp);
            if (!cacheHit) {
                convCache.put(epochIdx, fp); // 更新指纹
            }

            newSegments.add(new ContextSegment(seg.getEpoch(), content, cacheHit));
        }

        return new ContextResult(newSegments);
    }

    /** 使指定对话的缓存失效。 */
    public void invalidate(UUID conversationId) {
        if (conversationId != null) {
            cache.remove(conversationId);
        }
    }

    /** 获取指定对话的缓存统计。 */
    public CacheStats getStats(UUID conversationId) {
        Map<Integer, String> convCache = cache.get(conversationId);
        if (convCache == null) return new CacheStats(0, 0);
        int total = convCache.size();
        return new CacheStats(total, total);
    }

    /** 清空所有缓存。 */
    public void clear() {
        cache.clear();
    }

    /** 缓存条目数。 */
    public int size() {
        return cache.size();
    }

    /** 使用简单哈希作为指纹（快速，零依赖）。 */
    private static String fingerprint(String content) {
        if (content == null || content.isEmpty()) return "";
        int hash = content.hashCode();
        int length = content.length();
        return length + ":" + Integer.toHexString(hash);
    }

    /** 缓存统计。 */
    public record CacheStats(int epochCount, int cachedEpochCount) {
        public double hitRate() {
            return epochCount > 0 ? (double) cachedEpochCount / epochCount : 0;
        }
    }
}
