package com.icusu.sivan.web.forest.controller;

import com.icusu.sivan.agent.prompt.IntentClassifier;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import com.icusu.sivan.application.conversation.dto.*;
import com.icusu.sivan.application.forest.dto.ForestTreeResponse;
import com.icusu.sivan.application.forest.ForestConversationService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forest 对话与消息管理控制器 — V2 统一端点。
 * <p>
 * 替换 V1 {@code /api/conversations} 路径，提供相同的完整对话管理能力，
 * 内部使用 {@link com.icusu.sivan.infra.forest.execution.ForestExecutor} 执行。
 */
@RestController
@RequestMapping("/api/v2/conversations")
@RequiredArgsConstructor
public class ForestConversationController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ForestConversationController.class);


    private final ForestConversationService forestConversationService;
    private final StreamManager streamManager;
    private final com.icusu.sivan.application.conversation.MessageCrudService messageCrudService;
    private final IntentClassifier intentClassifier;

    // ============ 对话 CRUD ============

    /**
     * 创建会话。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request,
                                                      @CurrentAccountId UUID accountId) {
        return BaseResponse.created(forestConversationService.create(accountId, request));
    }

    /**
     * 根据 ID 获取会话。
     */
    @GetMapping("/{conversationId}")
    public BaseResponse<ConversationResponse> getById(@PathVariable UUID conversationId,
                                                       @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.getById(accountId, conversationId));
    }

    /**
     * 获取会话列表，可按项目 ID 过滤。
     */
    @GetMapping
    public BaseResponse<List<ConversationResponse>> list(@RequestParam(required = false) UUID projectId,
                                                          @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.list(accountId, projectId));
    }

    /**
     * 更新会话信息。
     */
    @PutMapping("/{conversationId}")
    public BaseResponse<ConversationResponse> update(@PathVariable UUID conversationId,
                                                      @Valid @RequestBody UpdateConversationRequest request,
                                                      @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.update(accountId, conversationId, request));
    }

    /**
     * 删除会话。
     */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID conversationId,
                                      @CurrentAccountId UUID accountId) {
        forestConversationService.delete(accountId, conversationId);
        return BaseResponse.success();
    }

    // ============ 消息 CRUD ============

    /**
     * 发送消息（非流式）。
     */
    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<MessageResponse> sendMessage(@PathVariable UUID conversationId,
                                                      @Valid @RequestBody SendMessageRequest request,
                                                      @CurrentAccountId UUID accountId) {
        return BaseResponse.created(forestConversationService.sendMessage(accountId, conversationId, request));
    }

    /**
     * 流式发送消息，返回 SSE 事件流。
     */
    @PostMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMessage(@PathVariable UUID conversationId,
                                       @Valid @RequestBody SendMessageRequest request,
                                       @CurrentAccountId UUID accountId) {
        return forestConversationService.streamMessage(accountId, conversationId, request);
    }

    /**
     * 取消流式消息生成。
     */
    @PostMapping("/{conversationId}/stream/{messageId}/cancel")
    public BaseResponse<Void> cancelStream(@PathVariable UUID conversationId,
                                            @PathVariable UUID messageId,
                                            @CurrentAccountId UUID accountId) {
        forestConversationService.cancelStream(accountId, conversationId, messageId);
        return BaseResponse.success();
    }

    /**
     * 重新生成消息，返回 SSE 事件流。
     */
    @PostMapping(value = "/{conversationId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> regenerate(@PathVariable UUID conversationId,
                                    @Valid @RequestBody RegenerateRequest request,
                                    @CurrentAccountId UUID accountId) {
        return forestConversationService.regenerateMessage(accountId, conversationId, request);
    }

    /**
     * 订阅消息流式输出。
     */
    @GetMapping(value = "/{conversationId}/messages/{messageId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> subscribeMessage(@PathVariable UUID conversationId,
                                          @PathVariable UUID messageId,
                                          @CurrentAccountId UUID accountId) {
        return forestConversationService.subscribeStream(accountId, conversationId, messageId);
    }

    /**
     * 获取消息编排进度（用于断连恢复）。
     */
    @GetMapping("/{conversationId}/messages/{messageId}/progress")
    public BaseResponse<Map<String, Object>> getMessageProgress(@PathVariable UUID conversationId,
                                                                 @PathVariable UUID messageId,
                                                                 @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.getMessageProgress(accountId, conversationId, messageId));
    }

    /**
     * 获取会话消息列表，支持分页。
     */
    @GetMapping("/{conversationId}/messages")
    public BaseResponse<MessagePageResponse> getMessages(@PathVariable UUID conversationId,
                                                          @RequestParam(required = false) Integer before,
                                                          @RequestParam(defaultValue = "50") int limit,
                                                          @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.getMessages(accountId, conversationId, before, limit));
    }

    /**
     * 获取会话消息总数。
     */
    @GetMapping("/{conversationId}/messages/count")
    public BaseResponse<Integer> countMessages(@PathVariable UUID conversationId,
                                                @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.countMessages(accountId, conversationId));
    }

    /** 查询对话关联的 GoalTree 列表（14-对话管理 §4）。 */
    @GetMapping("/{conversationId}/goals")
    public BaseResponse<List<Map<String, Object>>> getConversationGoals(
            @PathVariable UUID conversationId,
            @CurrentAccountId UUID accountId) {
        List<com.icusu.sivan.domain.forest.Forest> forests =
                forestConversationService.getForestsByConversation(accountId, conversationId);
        List<Map<String, Object>> items = forests.stream()
                .map(f -> Map.<String, Object>of(
                        "goalId", f.forestId().toString(),
                        "title", f.title() != null ? f.title() : "",
                        "rootNodeId", f.rootNodeId(),
                        "createdAt", f.createdAt() != null ? f.createdAt().toString() : ""
                ))
                .toList();
        return BaseResponse.success(items);
    }

    /** 全文搜索消息（14-对话管理 §3）。 */
    @GetMapping("/messages/search")
    public BaseResponse<List<MessageResponse>> searchMessages(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentAccountId UUID accountId) {
        return BaseResponse.success(messageCrudService.searchMessages(accountId, keyword, page, size));
    }

    /**
     * 获取消息的历史生成版本列表。
     */
    @GetMapping("/{conversationId}/messages/{messageId}/generations")
    public BaseResponse<List<MessageResponse>> getGenerations(@PathVariable UUID conversationId,
                                                               @PathVariable UUID messageId,
                                                               @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.getGenerations(accountId, conversationId, messageId));
    }

    /**
     * 删除单条消息。
     */
    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteMessage(@PathVariable UUID messageId,
                                             @CurrentAccountId UUID accountId) {
        forestConversationService.deleteMessage(accountId, messageId);
        return BaseResponse.success();
    }

    /**
     * 评价消息（赞/踩）— 树级反馈，用于意图分类学习。
     */
    @PatchMapping("/messages/{messageId}/rating")
    public BaseResponse<MessageResponse> rateMessage(@PathVariable UUID messageId,
                                                      @RequestParam String rating,
                                                      @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.rateMessage(accountId, messageId, rating));
    }

    /**
     * 评价执行树节点 — 节点级反馈，用于执行质量评估。
     */
    @PostMapping("/node-feedback")
    public BaseResponse<Void> nodeFeedback(@RequestBody Map<String, String> body,
                                            @CurrentAccountId UUID accountId) {
        String nodeName = body.get("nodeName");
        String rating = body.get("rating");
        if (nodeName == null || rating == null) {
            throw new IllegalArgumentException("nodeName 和 rating 不能为空");
        }
        if (!"like".equals(rating) && !"dislike".equals(rating)) {
            throw new IllegalArgumentException("rating 必须为 like 或 dislike");
        }
        intentClassifier.logNodeFeedback(accountId, nodeName, rating);
        // 踩 → 更新 Beta 参数，降低该节点对应的 Agent 下次被推荐的概率
        if ("dislike".equals(rating)) {
            try {
                forestConversationService.recordNodeBetaFeedback(accountId, nodeName, false);
            } catch (Exception e) {
                log.debug("[反馈] 节点反馈更新 Beta 失败: {}", e.getMessage());
            }
        }
        return BaseResponse.success();
    }

    /**
     * Flashback 推送 SSE — 订阅历史记忆闪现。
     */
    @GetMapping(value = "/flashback/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> flashbackStream(@CurrentAccountId UUID accountId) {
        return streamManager.subscribeFlashback()
                .filter(json -> json.contains(accountId.toString()) || json.contains("flashback"))
                .map(json -> "event: flashback\ndata: " + json + "\n\n");
    }

    /**
     * 获取消息的 Forest 执行树（供 PipelineDialog 展示）。
     */
    @GetMapping("/{conversationId}/messages/{messageId}/forest")
    public BaseResponse<ForestTreeResponse> getMessageForest(@PathVariable UUID conversationId,
                                                              @PathVariable UUID messageId,
                                                              @CurrentAccountId UUID accountId) {
        return BaseResponse.success(forestConversationService.getMessageForest(accountId, conversationId, messageId));
    }
}
