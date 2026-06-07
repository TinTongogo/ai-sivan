package com.icusu.sivan.web.conversation.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.conversation.service.ConversationService;
import com.icusu.sivan.web.orchestration.dto.PipelineStepResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * 会话与消息管理控制器。
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 创建会话。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(conversationService.create(accountId, request));
    }

    /**
     * 根据 ID 获取会话。
     */
    @GetMapping("/{conversationId}")
    public BaseResponse<ConversationResponse> getById(@PathVariable UUID conversationId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.getById(accountId, conversationId));
    }

    /**
     * 获取会话列表，可按项目 ID 过滤。
     */
    @GetMapping
    public BaseResponse<List<ConversationResponse>> list(@RequestParam(required = false) UUID projectId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.list(accountId, projectId));
    }

    /**
     * 更新会话信息。
     */
    @PutMapping("/{conversationId}")
    public BaseResponse<ConversationResponse> update(@PathVariable UUID conversationId,
                                                     @Valid @RequestBody UpdateConversationRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.update(accountId, conversationId, request));
    }

    /**
     * 删除会话。
     */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID conversationId, @CurrentAccountId UUID accountId) {
                conversationService.delete(accountId, conversationId);
        return BaseResponse.success();
    }

    /**
     * 发送消息（非流式）。
     */
    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<MessageResponse> sendMessage(@PathVariable UUID conversationId,
                                                     @Valid @RequestBody SendMessageRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(conversationService.sendMessage(accountId, conversationId, request));
    }

    /**
     * 流式发送消息，返回 SSE 事件流。
     */
    @PostMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMessage(@PathVariable UUID conversationId,
                                      @Valid @RequestBody SendMessageRequest request, @CurrentAccountId UUID accountId) {
                return conversationService.streamMessage(accountId, conversationId, request);
    }

    /**
     * 取消流式消息生成。
     */
    @PostMapping("/{conversationId}/stream/{messageId}/cancel")
    public BaseResponse<Void> cancelStream(@PathVariable UUID conversationId,
                                           @PathVariable UUID messageId, @CurrentAccountId UUID accountId) {
                conversationService.cancelStream(accountId, conversationId, messageId);
        return BaseResponse.success();
    }

    /**
     * 重新生成消息，返回 SSE 事件流。
     */
    @PostMapping(value = "/{conversationId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> regenerate(@PathVariable UUID conversationId,
                                   @Valid @RequestBody RegenerateRequest request, @CurrentAccountId UUID accountId) {
                return conversationService.regenerateMessage(accountId, conversationId, request);
    }

    /**
     * 订阅消息流式输出。
     */
    @GetMapping(value = "/{conversationId}/messages/{messageId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> subscribeMessage(@PathVariable UUID conversationId,
                                         @PathVariable UUID messageId, @CurrentAccountId UUID accountId) {
                return conversationService.subscribeStream(accountId, conversationId, messageId);
    }

    /**
     * 获取会话消息列表，支持分页。
     */
    @GetMapping("/{conversationId}/messages")
    public BaseResponse<MessagePageResponse> getMessages(@PathVariable UUID conversationId,
                                                         @RequestParam(required = false) Integer before,
                                                         @RequestParam(defaultValue = "50") int limit, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.getMessages(accountId, conversationId, before, limit));
    }

    /**
     * 获取会话消息总数。
     */
    @GetMapping("/{conversationId}/messages/count")
    public BaseResponse<Integer> countMessages(@PathVariable UUID conversationId,
                                               @CurrentAccountId UUID accountId) {
        return BaseResponse.success(conversationService.countMessages(accountId, conversationId));
    }

    /**
     * 获取消息的历史生成版本列表。
     */
    @GetMapping("/{conversationId}/messages/{messageId}/generations")
    public BaseResponse<List<MessageResponse>> getGenerations(@PathVariable UUID conversationId,
                                                              @PathVariable UUID messageId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.getGenerations(accountId, conversationId, messageId));
    }

    /**
     * 删除单条消息。
     */
    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteMessage(@PathVariable UUID messageId, @CurrentAccountId UUID accountId) {
                conversationService.deleteMessage(accountId, messageId);
        return BaseResponse.success();
    }

    /**
     * 评价消息（赞/踩）。
     */
    @PatchMapping("/messages/{messageId}/rating")
    public BaseResponse<MessageResponse> rateMessage(@PathVariable UUID messageId,
                                                     @RequestParam String rating, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(conversationService.rateMessage(accountId, messageId, rating));
    }

    /**
     * 获取消息的编排流水线步骤。
     */
    @GetMapping("/messages/{messageId}/pipeline-steps")
    public BaseResponse<List<PipelineStepResponse>> getPipelineSteps(
            @PathVariable UUID messageId, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(conversationService.getPipelineSteps(accountId, messageId));
    }

}
