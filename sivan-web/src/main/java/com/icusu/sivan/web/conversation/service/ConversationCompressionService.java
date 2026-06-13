package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.web.conversation.service.compress.ConversationCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 对话压缩服务 — 管理历史压缩的快照优先策略与异步压缩生命周期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationCompressionService {

    private final IConversationRepository conversationRepository;
    private final ConversationCompressor conversationCompressor;
    private final HistoryCompressor historyCompressor;

    /**
     * 异步压缩 Future 追踪（conversationId → CompletableFuture），
     * 用于 resolveCompressResult 等待进行中的异步压缩完成。
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> compressionFutures = new ConcurrentHashMap<>();

    /**
     * 优先读快照，无快照时检查是否有进行中的异步压缩。
     */
    public Mono<CompressResult> resolveCompressResult(Conversation conversation, UUID conversationId,
                                                       int historyBudget, UUID accountId,
                                                       Consumer<String> progress, String currentQuery) {
        return resolveCompressResult(conversation, conversationId, historyBudget, accountId, progress, currentQuery, null);
    }

    /**
     * 优先读快照，无快照时检查是否有进行中的异步压缩（支持预加载消息列表）。
     */
    public Mono<CompressResult> resolveCompressResult(Conversation conversation, UUID conversationId,
                                                       int historyBudget, UUID accountId,
                                                       Consumer<String> progress, String currentQuery,
                                                       List<Message> allMessages) {
        String cached = conversation.getCompressedContext();
        if (cached != null && !cached.isBlank()) {
            log.debug("使用延迟压缩快照: conversationId={}, contextLen={}", conversationId, cached.length());
            return Mono.just(new CompressResult(cached, List.of(), List.of(), conversation.getCompressedUpToMsgId(), true));
        }

        CompletableFuture<String> pending = compressionFutures.get(conversationId);
        if (pending != null && !pending.isDone()) {
            log.debug("等待异步压缩完成: conversationId={}", conversationId);
            return Mono.fromFuture(pending)
                    .flatMap(summary -> {
                        if (summary != null && !summary.isBlank()) {
                            return Mono.just(new CompressResult(summary, List.of(), List.of(),
                                    conversation.getCompressedUpToMsgId(), true));
                        }
                        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
                    })
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> {
                        log.warn("等待异步压缩超时/异常，执行同步压缩: conversationId={}", conversationId, e);
                        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
                    });
        }

        return syncCompress(conversationId, historyBudget, currentQuery, allMessages);
    }

    /**
     * 使用预加载消息或从 repository 加载执行同步压缩。
     */
    public Mono<CompressResult> syncCompress(UUID conversationId, int historyBudget, String currentQuery, List<Message> allMessages) {
        if (allMessages != null) {
            return conversationCompressor.compress(allMessages, historyBudget, currentQuery);
        }
        return conversationCompressor.compress(conversationId, historyBudget, currentQuery);
    }

    /**
     * 异步触发压缩，结果写入 Conversation 快照。不阻塞当前请求。
     */
    public void scheduleAsyncCompression(UUID conversationId, UUID accountId, UUID upToMsgId,
                                          int contextLength, double budgetRatio) {
        int historyBudget = (int) (contextLength * budgetRatio * 0.85);
        Consumer<String> progress = msg -> log.debug("异步压缩: {}", msg);

        CompletableFuture<String> future = new CompletableFuture<>();
        compressionFutures.put(conversationId, future);
        future.whenComplete((r, e) -> compressionFutures.remove(conversationId));

        Mono.defer(() -> {
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) return Mono.empty();
            conv.setCompressedUpToMsgId(upToMsgId);
            conversationRepository.update(conv);
            return Mono.just(conv);
        }).flatMap(conv ->
                historyCompressor.compressStream(conversationId, historyBudget, accountId, progress)
                        .flatMap(result -> {
                            String summary = result.toSummaryText();
                            if (summary != null && !summary.isBlank()) {
                                conv.setCompressedContext(summary);
                                conversationRepository.update(conv);
                                future.complete(summary);
                            } else {
                                future.complete("");
                            }
                            return Mono.empty();
                        })
        ).onErrorResume(e -> {
            log.warn("异步压缩失败: conversationId={}, {}", conversationId, e.getMessage());
            future.completeExceptionally(e);
            return Mono.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe(null, err -> {
            log.warn("异步压缩异常: {}", err.getMessage());
            future.completeExceptionally(err);
        });
    }
}
