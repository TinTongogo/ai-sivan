package com.icusu.sivan.infra.memory.flashback;

import com.icusu.sivan.domain.memory.flashback.FlashbackCandidate;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.infra.shared.sse.SseFormatter;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 闪现引擎 — 消息到来时做语义匹配，高关联度时主动推送到前端。
 * <p>
 * 与注入式的 {@link FlashbackScanner} 不同，FlashbackEngine 走推送通道，
 * 前端以独立气泡展示。设计文档 13-记忆与闪现。
 */
@Component
public class FlashbackEngine {

    private static final Logger log = LoggerFactory.getLogger(FlashbackEngine.class);

    private final FlashbackScanner scanner;
    private final IEmbeddingService embeddingService;
    private final FlashbackThrottle throttle;
    private final StreamManager streamManager;

    /** 弱关联暂存区：accountId → PendingFlash */
    private final Map<UUID, PendingFlash> pendingFlashes = new ConcurrentHashMap<>();

    public FlashbackEngine(FlashbackScanner scanner, IEmbeddingService embeddingService,
                           FlashbackThrottle throttle, StreamManager streamManager) {
        this.scanner = scanner;
        this.embeddingService = embeddingService;
        this.throttle = throttle;
        this.streamManager = streamManager;
    }

    /**
     * 新消息到来时触发。
     * @param content   消息内容
     * @param accountId 账户 ID
     * @param conversationId 对话 ID
     */
    public void onNewMessage(String content, UUID accountId, UUID conversationId) {
        if (content == null || content.isBlank()) return;

        // 先检查暂存区
        confirmPending(accountId, content);

        try {
            float[] vec = embeddingService.embed(content);
            List<FlashbackCandidate> candidates = scanner.scan(accountId, content, 3);
            if (candidates.isEmpty()) return;

            double topScore = candidates.getFirst().getRelevanceScore();

            if (topScore > 0.85) {
                // 强关联 → 立即推送
                pushFlash(candidates.getFirst(), content, accountId, conversationId);
            } else if (topScore > 0.75) {
                // 弱关联 → 暂存，等待下一条确认
                pendingFlashes.put(accountId, new PendingFlash(vec, candidates.getFirst(), content));
                log.debug("[Flashback] 弱关联暂存: accountId={} score={}",
                        accountId.toString().substring(0, 8), String.format("%.2f", topScore));
            }
        } catch (Exception e) {
            log.warn("[Flashback] 处理异常(不影响主流程): {}", e.getMessage());
        }
    }

    /** 暂存区确认：下一条消息来临时检查是否同一主题。 */
    void confirmPending(UUID accountId, String nextContent) {
        PendingFlash pending = pendingFlashes.remove(accountId);
        if (pending == null) return;

        try {
            float[] nextVec = embeddingService.embed(nextContent);
            double sim = cosineSimilarity(pending.contextVec, nextVec);
            if (sim > 0.80) {
                pushFlash(pending.candidate, pending.originalContent, accountId, null);
            }
        } catch (Exception e) {
            log.warn("[Flashback] 暂存确认失败: {}", e.getMessage());
        }
    }

    private void pushFlash(FlashbackCandidate candidate, String currentContent,
                           UUID accountId, UUID conversationId) {
        if (!throttle.allowPush(accountId, candidate.getRelevanceScore())) return;

        String timeAgo = timeAgo(null);
        String message = String.format("关于「%s」，你在 %s 有相关的记录。",
                currentContent, timeAgo);

        String json = SseFormatter.buildMatchTemplateEvent("💡 " + message);
        streamManager.emitFlashback(accountId, json);

        log.info("[Flashback] 已推送: accountId={} score={}",
                accountId.toString().substring(0, 8),
                String.format("%.2f", candidate.getRelevanceScore()));
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String timeAgo(LocalDateTime dt) {
        if (dt == null) return "过去";
        Duration d = Duration.between(dt, LocalDateTime.now());
        if (d.toDays() > 30) return (d.toDays() / 30) + "个月前";
        if (d.toDays() > 0) return d.toDays() + "天前";
        if (d.toHours() > 0) return d.toHours() + "小时前";
        return "刚才";
    }

    record PendingFlash(float[] contextVec, FlashbackCandidate candidate, String originalContent) {}
}
