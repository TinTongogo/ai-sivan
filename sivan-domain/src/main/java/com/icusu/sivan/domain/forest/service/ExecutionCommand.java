package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Flux;

/** 封装一次执行请求，用于调度排队。 */
public record ExecutionCommand(
        ExecutableNode root,
        ExecutionContext ctx,
        int priority,
        long submittedAt
) {}
