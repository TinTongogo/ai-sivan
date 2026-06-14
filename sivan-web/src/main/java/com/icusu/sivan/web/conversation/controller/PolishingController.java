package com.icusu.sivan.web.conversation.controller;

import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.util.JsonUtil;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.UUID;
import com.icusu.sivan.application.conversation.dto.PolishRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 文本润色控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PolishingController {

    private final ModelRouter modelRouter;

    /**
     * 同步润色（非流式）。
     */
    @PostMapping("/polish")
    public Mono<BaseResponse<Map<String, String>>> polish(@Valid @RequestBody PolishRequest request, @CurrentAccountId UUID accountId) {
                List<Msg> msgs = List.of(
                Msg.of(Role.USER, ChatPrompts.POLISH_SYSTEM.content()),
                Msg.of(Role.USER, request.getText())
        );
        return modelRouter.getDefaultModel(accountId).chat(msgs, Model.ModelParams.defaults())
                .map(response -> BaseResponse.success(Map.of("polished", response.msg().text())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式润色。返回 SSE 事件流，每个事件格式：{"type":"response","content":"..."}
     */
    @PostMapping(value = "/polish/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> polishStream(@Valid @RequestBody PolishRequest request, @CurrentAccountId UUID accountId) {
                List<Msg> msgs = List.of(
                Msg.of(Role.USER, ChatPrompts.POLISH_SYSTEM.content()),
                Msg.of(Role.USER, request.getText())
        );
        return modelRouter.getDefaultModel(accountId).stream(msgs, Model.ModelParams.defaults())
                .map(chunk -> "{\"type\":\"response\",\"content\":\"" + JsonUtil.escapeJson(chunk.content()) + "\"}")
                .subscribeOn(Schedulers.boundedElastic());
    }
}
