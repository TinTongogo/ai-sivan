package com.icusu.sivan.web.goal.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.goal.dto.AppendTaskRequest;
import com.icusu.sivan.web.goal.dto.ArtifactResponse;
import com.icusu.sivan.web.goal.dto.CreateGoalRequest;
import com.icusu.sivan.web.goal.dto.GoalProgressResponse;
import com.icusu.sivan.web.goal.dto.GoalResponse;
import com.icusu.sivan.web.goal.service.GoalService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 持续自主目标 REST 控制器。
 */
@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    /**
     * 创建目标（含 LLM 拆解）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<GoalResponse> create(@Valid @RequestBody CreateGoalRequest request,
                                              @CurrentAccountId UUID accountId) {
        return BaseResponse.created(goalService.create(request, accountId));
    }

    /**
     * 获取目标列表。
     */
    @GetMapping
    public BaseResponse<List<GoalResponse>> list(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(goalService.list(accountId));
    }

    /**
     * 获取目标详情。
     */
    @GetMapping("/{goalId}")
    public BaseResponse<GoalResponse> getById(@PathVariable UUID goalId,
                                               @CurrentAccountId UUID accountId) {
        return BaseResponse.success(goalService.getById(goalId, accountId));
    }

    /**
     * 按对话 ID 查询目标进度（供 PipelineDialog 展示）。
     */
    @GetMapping("/by-conversation/{conversationId}")
    public BaseResponse<GoalProgressResponse> getProgressByConversation(
            @PathVariable UUID conversationId,
            @CurrentAccountId UUID accountId) {
        GoalProgressResponse progress = goalService.getProgressByConversation(conversationId, accountId);
        if (progress == null) {
            return BaseResponse.success(null);
        }
        return BaseResponse.success(progress);
    }

    /**
     * 启动目标执行（SSE 流式返回执行事件）。
     */
    @PostMapping(value = "/{goalId}/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> start(@PathVariable UUID goalId,
                              @CurrentAccountId UUID accountId) {
        return goalService.startGoal(goalId, accountId);
    }

    /**
     * 暂停目标。
     */
    @PostMapping("/{goalId}/pause")
    public BaseResponse<Void> pause(@PathVariable UUID goalId,
                                    @CurrentAccountId UUID accountId,
                                    @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "用户主动暂停") : "用户主动暂停";
        goalService.pauseGoal(goalId, accountId, reason);
        return BaseResponse.success(null);
    }

    /**
     * 恢复目标执行（SSE 流式返回执行事件）。
     */
    @PostMapping(value = "/{goalId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> resume(@PathVariable UUID goalId,
                               @CurrentAccountId UUID accountId) {
        return goalService.resumeGoal(goalId, accountId);
    }

    /**
     * 取消目标。
     */
    @PostMapping("/{goalId}/cancel")
    public BaseResponse<Void> cancel(@PathVariable UUID goalId,
                                     @CurrentAccountId UUID accountId) {
        goalService.cancelGoal(goalId, accountId);
        return BaseResponse.success(null);
    }

    /**
     * 获取产物列表。
     */
    @GetMapping("/{goalId}/artifacts")
    public BaseResponse<List<ArtifactResponse>> listArtifacts(@PathVariable UUID goalId,
                                                               @CurrentAccountId UUID accountId) {
        return BaseResponse.success(goalService.listArtifacts(goalId, accountId));
    }

    /**
     * 读取产物文件内容。
     */
    @GetMapping("/{goalId}/artifacts/read")
    public BaseResponse<String> readArtifact(@PathVariable UUID goalId,
                                             @CurrentAccountId UUID accountId,
                                             @RequestParam String path) {
        String content = goalService.readArtifactContent(goalId, accountId, path);
        return BaseResponse.success(content);
    }

    /**
     * 追加 Task 到目标。
     */
    @PostMapping("/{goalId}/tasks")
    public BaseResponse<GoalResponse> appendTasks(@PathVariable UUID goalId,
                                                  @CurrentAccountId UUID accountId,
                                                  @Valid @RequestBody AppendTaskRequest request) {
        return BaseResponse.success(goalService.appendTasks(goalId, accountId, request));
    }
}
