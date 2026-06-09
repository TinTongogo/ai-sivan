package com.icusu.sivan.web.forest.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.service.TreeMatcher;
import com.icusu.sivan.infra.forest.execution.GoalExecutionService;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forest 对话 SSE 端点 — 使用 {@link TreeMatcher} 匹配用户输入并执行。
 */
@RestController
@RequestMapping("/api/v2/conversations")
public class ForestConversationController {
    private static final Logger log = LoggerFactory.getLogger(ForestConversationController.class);
    private final GoalExecutionService goalExecutionService;
    private final ForestRepository forestRepository;
    private final TreeMatcher treeMatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ForestConversationController(GoalExecutionService goalExecutionService,
                                        ForestRepository forestRepository,
                                        TreeMatcher treeMatcher) {
        this.goalExecutionService = goalExecutionService;
        this.forestRepository = forestRepository;
        this.treeMatcher = treeMatcher;
    }

    @PostMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ForestEvent> stream(@PathVariable String id,
                                     @RequestBody String body,
                                     @CurrentAccountId UUID accountId,
                                     @RequestHeader("Last-Event-ID") Optional<String> lastEventId,
                                     @RequestParam(defaultValue = "STREAM") String delivery) {
        var convId = UUID.fromString(id);

        // 处理重连：检查已有执行状态
        if (lastEventId.isPresent()) {
            log.info("[SSE] 重连: conversationId={} lastEventId={}", id, lastEventId.get());
            Forest existing = forestRepository.findForestById(convId, accountId);
            if (existing != null) {
                return Flux.just(ForestEvent.lifecycle(convId.toString(), null,
                        accountId.toString(), ForestEvent.EventType.LIFECYCLE));
            }
        }

        String content = body;
        try {
            var map = objectMapper.readValue(body, new TypeReference<Map<String, String>>() {});
            if (map.containsKey("content")) content = map.get("content");
        } catch (Exception ignored) {}

        Delivery mode = Delivery.valueOf(delivery.toUpperCase());
        log.info("[SSE] 开始执行: conversationId={} delivery={}", id, mode);

        // 使用 TreeMatcher 匹配用户输入，替代硬编码的树构建
        return treeMatcher.match(content, accountId)
                .flatMapMany(root -> {
                    var forest = new Forest(convId, accountId, null, "对话", root.nodeId());
                    return goalExecutionService.execute(forest, root,
                            ExecutionContext.create(accountId, null, convId), mode);
                });
    }
}
