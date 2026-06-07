package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.context.Epoch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContextCacheTest {

    private final UUID convId = UUID.randomUUID();

    private ContextResult makeResult(String historyContent, String activeContent) {
        return new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false),
                new ContextSegment(Epoch.EPOCH_1_PROFILE, "用户画像", false),
                new ContextSegment(Epoch.EPOCH_2_COMPLETED, "", false),
                new ContextSegment(Epoch.EPOCH_3_HISTORY, historyContent, false),
                new ContextSegment(Epoch.EPOCH_4_ACTIVE, activeContent, false)
        ));
    }

    @Test
    void firstCall_noCacheHits() {
        ContextCache cache = new ContextCache();
        ContextResult result = makeResult("历史摘要", "活跃消息");
        ContextResult cached = cache.apply(convId, result);

        // 首次调用全部 miss
        assertTrue(cached.getCachedEpochIndices().isEmpty());
    }

    @Test
    void secondCall_sameContent_cacheHitsForAll() {
        ContextCache cache = new ContextCache();
        ContextResult result = makeResult("历史摘要", "活跃消息");

        // 第一次调用：全 miss
        cache.apply(convId, result);
        // 第二次调用：全 hit（内容相同）
        ContextResult second = cache.apply(convId, result);

        assertFalse(second.getCachedEpochIndices().isEmpty());
        // 有内容的 epoch 应该全部命中
        assertTrue(second.getCachedEpochIndices().contains(3));
        assertTrue(second.getCachedEpochIndices().contains(4));
    }

    @Test
    void changedContent_onlyUnchangedEpochsHit() {
        ContextCache cache = new ContextCache();
        ContextResult first = makeResult("旧摘要", "旧活跃消息");
        cache.apply(convId, first);

        // 只有活跃消息变了，历史摘要没变
        ContextResult second = makeResult("旧摘要", "新活跃消息");
        ContextResult cached = cache.apply(convId, second);

        List<Integer> hits = cached.getCachedEpochIndices();
        assertTrue(hits.contains(3));  // 历史摘要未变 → hit
        assertFalse(hits.contains(4)); // 活跃消息变了 → miss
    }

    @Test
    void invalidate_clearsCache() {
        ContextCache cache = new ContextCache();
        ContextResult result = makeResult("摘要", "活跃");

        cache.apply(convId, result);
        cache.invalidate(convId);

        // 失效后重新调用应该全 miss
        ContextResult second = cache.apply(convId, result);
        assertTrue(second.getCachedEpochIndices().isEmpty());
    }

    @Test
    void nullConversationId_returnsUnchanged() {
        ContextCache cache = new ContextCache();
        ContextResult result = makeResult("摘要", "活跃");
        ContextResult applied = cache.apply(null, result);
        assertEquals(result.toFlatString(), applied.toFlatString());
    }

    @Test
    void nullResult_returnsNull() {
        ContextCache cache = new ContextCache();
        assertNull(cache.apply(convId, null));
    }

    @Test
    void emptyResult_returnsUnchanged() {
        ContextCache cache = new ContextCache();
        ContextResult empty = ContextResult.empty();
        ContextResult applied = cache.apply(convId, empty);
        assertTrue(applied.isEmpty());
    }

    @Test
    void clear_resetsAll() {
        ContextCache cache = new ContextCache();
        cache.apply(convId, makeResult("摘要", "活跃"));
        assertEquals(1, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void getStats_returnsMetrics() {
        ContextCache cache = new ContextCache();
        ContextResult result = makeResult("摘要", "活跃");
        cache.apply(convId, result);
        cache.apply(convId, result); // second call → hits

        ContextCache.CacheStats stats = cache.getStats(convId);
        assertTrue(stats.epochCount() > 0);
    }

    @Test
    void getStats_missingConversation_returnsZero() {
        ContextCache cache = new ContextCache();
        ContextCache.CacheStats stats = cache.getStats(UUID.randomUUID());
        assertEquals(0, stats.epochCount());
        assertEquals(0.0, stats.hitRate());
    }

    @Test
    void profileAndCompleted_emptyContent_cacheHitOnEpoch0Only() {
        ContextCache cache = new ContextCache();
        // Epoch 1 empty, Epoch 2 empty
        ContextResult first = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false),
                new ContextSegment(Epoch.EPOCH_1_PROFILE, "", false),
                new ContextSegment(Epoch.EPOCH_2_COMPLETED, "", false),
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "历史", false),
                new ContextSegment(Epoch.EPOCH_4_ACTIVE, "活跃", false)
        ));
        cache.apply(convId, first);

        ContextResult second = cache.apply(convId, first);
        // 空内容的 epoch 不产生 cache breakpoint（没有内容需要缓存）
        assertTrue(second.getCachedEpochIndices().contains(3));
        assertTrue(second.getCachedEpochIndices().contains(4));
    }
}
