package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.orch.strategy.PhaseSchedulingStrategy;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阶段间调度器。根据 SquadMode 委派给对应的 PhaseSchedulingStrategy 实现。
 * <p>
 * 五种阶段间模式：SEQUENTIAL / PARALLEL / CONDITIONAL / HIERARCHICAL / CONSENSUS
 */
@Slf4j
@Component
public class PhaseScheduler {

    private final Map<SquadMode, PhaseSchedulingStrategy> strategies;

    public PhaseScheduler(List<PhaseSchedulingStrategy> strategyBeans) {
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toMap(PhaseSchedulingStrategy::supportedMode, Function.identity()));
    }

    /**
     * 根据 SquadMode 生成调度计划。
     */
    public SchedulePlan schedule(List<PhaseNode> phases, SquadMode mode) {
        return getStrategy(mode).schedule(phases);
    }

    /**
     * 执行一组阶段，按指定编排模式调度（从 startPhase 开始）。
     */
    public Mono<PhaseResult> executeSquad(List<PhaseNode> phases, SquadMode mode, String input,
                                           UUID executionId, UUID accountId, PhaseCallbacks callbacks,
                                           int startPhase) {
        ContextPackage context = new ContextPackage(input);
        return executeSquad(phases, mode, context, executionId, accountId, callbacks, startPhase);
    }

    /**
     * 执行一组阶段（ContextPackage 版本）。
     */
    public Mono<PhaseResult> executeSquad(List<PhaseNode> phases, SquadMode mode, ContextPackage context,
                                           UUID executionId, UUID accountId, PhaseCallbacks callbacks,
                                           int startPhase) {
        return getStrategy(mode).execute(phases, context, executionId, accountId, callbacks, startPhase);
    }

    /**
     * 执行一组阶段，按指定编排模式调度（startPhase=0 的简写）。
     */
    public Mono<PhaseResult> executeSquad(List<PhaseNode> phases, SquadMode mode, String input,
                                           UUID executionId, UUID accountId, PhaseCallbacks callbacks) {
        return executeSquad(phases, mode, input, executionId, accountId, callbacks, 0);
    }

    private PhaseSchedulingStrategy getStrategy(SquadMode mode) {
        PhaseSchedulingStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的编排模式: " + mode);
        }
        return strategy;
    }
}
