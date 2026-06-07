package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.orch.executor.*;
import com.icusu.sivan.agent.prompt.OrchestrationPrompts;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.domain.orchestration.ContextPackage;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.PhaseOutput;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * CONDITIONAL 阶段间调度：每阶段执行后用 LLM 决策下一阶段，支持分支跳转。
 * <p>
 * 路由结果 LRU 缓存（128 条目，10 分钟 TTL）避免同类任务反复调 LLM。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionalSchedulingStrategy implements PhaseSchedulingStrategy {

    private final PhaseExecutor phaseExecutor;
    private final ModelRouter modelRouter;
    private final ISquadExecutionRepository squadExecutionRepository;

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private record CacheEntry(int value, long timestamp) {}

    private final LinkedHashMap<String, CacheEntry> routeCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
            return size() > 128;
        }
    };

    @Override
    public SquadMode supportedMode() {
        return SquadMode.CONDITIONAL;
    }

    @Override
    public SchedulePlan schedule(List<PhaseNode> phases) {
        List<PhaseNode> valid = phases != null ? phases : List.of();
        return new SchedulePlan(valid.stream()
                .map(p -> new ScheduleGroup(GroupMode.SEQUENTIAL, List.of(p)))
                .toList());
    }

    @Override
    public Mono<PhaseResult> execute(List<PhaseNode> phases, ContextPackage context,
                                     UUID executionId, UUID accountId,
                                     PhaseCallbacks callbacks, int startPhase) {
        return executeLoop(phases, context, executionId, accountId, callbacks,
                startPhase > 0 ? startPhase : 0);
    }

    /** 递归执行条件阶段链。dependsOn 未就绪时跳过，到头后重扫。 */
    private Mono<PhaseResult> executeLoop(List<PhaseNode> phases, ContextPackage context,
                                           UUID executionId, UUID accountId,
                                           PhaseCallbacks callbacks, int phaseIndex) {
        String currentContent = context.getInput();

        // 跳过 dependsOn 未就绪的阶段
        int effectiveIdx = findNextReady(phases, context, phaseIndex);
        if (effectiveIdx >= phases.size()) {
            // 检查是否全部完成
            boolean allDone = phases.stream()
                    .filter(p -> p.getAgents() != null && !p.getAgents().isEmpty())
                    .allMatch(p -> context.getPhaseOutputs().containsKey(p.getPhase()));
            if (allDone) return Mono.just(PhaseResult.success(currentContent));
            // 有未完成但无就绪 → 重扫（可能刚完成的阶段解锁了依赖）
            return executeLoop(phases, context, executionId, accountId, callbacks, 0);
        }

        PhaseNode phase = phases.get(effectiveIdx);
        if (callbacks.isCancelled(executionId)) return Mono.just(PhaseResult.paused(currentContent, "CANCELLED"));
        if (callbacks.isTimedOut(executionId)) return Mono.just(PhaseResult.paused(currentContent, "TIMEOUT"));

        PhaseResult pre = callbacks.preDispatchPhase(phase, effectiveIdx, currentContent, executionId);
        if (pre != null) return Mono.just(pre);

        boolean hasAgents = phase.getAgents() != null && !phase.getAgents().isEmpty();
        final long phaseStartMs = hasAgents ? System.currentTimeMillis() : 0;
        Mono<PhaseResult> execMono = hasAgents
                ? phaseExecutor.dispatchPhase(phase, currentContent, executionId, accountId, effectiveIdx, callbacks, null)
                : Mono.just(PhaseResult.success(currentContent));

        return execMono.flatMap(r -> {
            if (r.paused()) {
                callbacks.onPhasePaused(phase, effectiveIdx, currentContent, r);
                return Mono.just(PhaseResult.paused(currentContent, r.pauseReason()));
            }
            if (hasAgents) {
                long durationMs = System.currentTimeMillis() - phaseStartMs;
                callbacks.onPhaseCompleted(phase, effectiveIdx, r.content(), durationMs);
            }
            squadExecutionRepository.updateCurrentPhase(executionId, effectiveIdx);
            PhaseOutput condPhaseOutput = new PhaseOutput(r.content());
            callbacks.onArtifactGenerated(executionId, effectiveIdx, condPhaseOutput);
            ContextPackage updated = context.withPhaseOutput(effectiveIdx, condPhaseOutput);

            if (effectiveIdx < phases.size() - 1) {
                return decideNextPhase(phases, effectiveIdx, r.content(), executionId, accountId)
                        .flatMap(next -> {
                            if (next < 0) {
                                // LLM 判定执行完成
                                return Mono.just(PhaseResult.success(r.content()));
                            }
                            // LLM 选择的下一阶段可能未就绪 → 重扫
                            if (next < phases.size()
                                    && !DagScheduleHelper.isReady(phases.get(next), updated)) {
                                return executeLoop(phases, updated, executionId, accountId, callbacks, 0);
                            }
                            return executeLoop(phases, updated, executionId, accountId, callbacks, next);
                        });
            }
            return Mono.just(PhaseResult.success(r.content()));
        });
    }

    /** 从指定索引开始找下一个就绪且未执行的阶段。返回 phases.size() 表示未找到。 */
    private int findNextReady(List<PhaseNode> phases, ContextPackage context, int fromIndex) {
        for (int i = fromIndex; i < phases.size(); i++) {
            PhaseNode p = phases.get(i);
            if (p.getAgents() == null || p.getAgents().isEmpty()) continue;
            if (context.getPhaseOutputs().containsKey(p.getPhase())) continue;
            if (DagScheduleHelper.isReady(p, context)) return i;
        }
        return phases.size();
    }

    /** LLM 决策下一阶段，命中有效缓存则跳过 LLM 调用。 */
    private Mono<Integer> decideNextPhase(List<PhaseNode> phases, int currentIndex,
                                           String output, UUID executionId, UUID accountId) {
        String cacheKey = currentIndex + ":" + (output.length() > 200 ? output.substring(0, 200) : output);

        synchronized (routeCache) {
            CacheEntry entry = routeCache.get(cacheKey);
            if (entry != null && (System.currentTimeMillis() - entry.timestamp()) < CACHE_TTL_MS) {
                log.debug("CONDITIONAL 路由命中缓存: 阶段 {} → {}", currentIndex, entry.value());
                return Mono.just(entry.value());
            } else if (entry != null) {
                routeCache.remove(cacheKey);
            }
        }

        List<OrchestrationPrompts.PhaseInfo> phaseInfos = new java.util.ArrayList<>();
        for (int j = currentIndex + 1; j < phases.size(); j++) {
            phaseInfos.add(new OrchestrationPrompts.PhaseInfo(phases.get(j).getName(), phases.get(j).getDescription()));
        }
        String routePrompt = OrchestrationPrompts.ORCHESTRATION_SYSTEM.content() + "\n\n"
                + OrchestrationPrompts.conditionalRouteUser(phaseInfos, currentIndex,
                output.length() > 300 ? output.substring(0, 300) + "..." : output).content();

        return modelRouter.getDefaultModel(accountId).chat(
                List.of(Msg.of(Role.USER, routePrompt)),
                ModelParams.defaults()
        ).map(response -> {
            String decision = response != null ? response.msg().text() : "";
            try {
                int next = Integer.parseInt(decision.strip());
                long now = System.currentTimeMillis();
                synchronized (routeCache) {
                    if (next == -1 || next >= phases.size()) {
                        routeCache.put(cacheKey, new CacheEntry(-1, now));
                        return -1;
                    }
                    routeCache.put(cacheKey, new CacheEntry(next, now));
                }
                log.info("CONDITIONAL 路由: 阶段 {} → {}", currentIndex, next);
                return next;
            } catch (NumberFormatException e) {
                log.warn("CONDITIONAL 路由解析失败: {}", decision);
                return currentIndex + 1;
            }
        });
    }
}
