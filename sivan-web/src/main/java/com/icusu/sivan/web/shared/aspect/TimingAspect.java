package com.icusu.sivan.web.shared.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 编排流水线各阶段耗时切面。统一以 [Perf] 前缀输出。
 * Mono 在 doOnSuccess（值产生时）记录，Flux 在 doOnComplete（流结束时）记录，
 * 避免 doFinally 被下游操作延续生命周期。
 */
@Aspect
@Component
@Slf4j
public class TimingAspect {

    // ===== 全链路入口 =====

    @Around("execution(* com.icusu.sivan.web.conversation.service.ConversationService.streamMessage(..))")
    public Object measureStreamMessage(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "1-streamMessage");
    }

    // ===== 意图解析 =====

    @Around("execution(* com.icusu.sivan.orch.executor.SquadOrchestrator.resolveIntent(..))")
    public Object measureResolveIntent(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "2-resolveIntent");
    }

    @Around("execution(* com.icusu.sivan.orch.topology.ExecutionPathResolver.resolve(..))")
    public Object measureResolve(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "2a-resolveTemplate");
    }

    // ===== 编排调度 =====

    @Around("execution(* com.icusu.sivan.orch.executor.SquadOrchestrator.orchestrateStream(..))")
    public Object measureOrchestrate(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "3-orchestrateStream");
    }

    @Around("execution(* com.icusu.sivan.orch.executor.OrchestrationDispatcher.dispatch(..))")
    public Object measureDispatch(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "3a-dispatcher");
    }

    // ===== 策略执行 =====

    @Around("execution(* com.icusu.sivan.orch.strategy.ChatFallbackStrategy.execute(..))")
    public Object measureChatStrategy(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "4-chatStrategy");
    }

    @Around("execution(* com.icusu.sivan.orch.strategy.SingleAgentStrategy.execute(..))")
    public Object measureSingleAgentStrategy(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "4-singleAgentStrategy");
    }

    @Around("execution(* com.icusu.sivan.orch.strategy.SquadStrategy.execute(..))")
    public Object measureSquadStrategy(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "4-squadStrategy");
    }

    @Around("execution(* com.icusu.sivan.agent.strategy.ReActExecutionStrategy.execute(..))")
    public Object measureReAct(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "4a-reActExecute");
    }

    // ===== 模型路由 =====

    @Around("execution(* com.icusu.sivan.agent.model.DefaultModelRouter.getDefaultModel(..))")
    public Object measureGetDefaultModel(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "5-getDefaultModel");
    }

    @Around("execution(* com.icusu.sivan.agent.model.DefaultModelRouter.getModel(..))")
    public Object measureGetModel(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "5a-getModel");
    }

    @Around("execution(* com.icusu.sivan.agent.model.DefaultModelRouter.getDefaultProvider(..))")
    public Object measureGetDefaultProvider(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "5b-getDefaultProvider");
    }

    // ===== 上下文构建 =====

    @Around("execution(* com.icusu.sivan.web.conversation.service.tree.ContextBuilder.buildEpochs(..))")
    public Object measureBuildEpochs(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "6-buildEpochs");
    }

    @Around("execution(* com.icusu.sivan.web.knowledge.service.RagContextBuilder.build(..))")
    public Object measureRagBuild(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "6a-ragBuild");
    }

    @Around("execution(* com.icusu.sivan.web.conversation.service.HistoryCompressor.compressStream(..))")
    public Object measureCompress(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "6b-compressStream");
    }

    // ===== 流管理 =====

    @Around("execution(* com.icusu.sivan.infra.shared.sse.StreamManager.create(..))")
    public Object measureStreamCreate(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "7-streamCreate");
    }

    @Around("execution(* com.icusu.sivan.web.conversation.service.StreamingMessageEngine.register(..))")
    public Object measureEngineRegister(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "7a-engineRegister");
    }

    private Object measure(ProceedingJoinPoint pjp, String stage) throws Throwable {
        long t0 = System.nanoTime();
        Object result = pjp.proceed();

        if (result instanceof Mono<?> mono) {
            return mono.doOnSuccess(v -> log.info("[Perf] {}={}μs", stage, (System.nanoTime() - t0) / 1000));
        }
        if (result instanceof Flux<?> flux) {
            return flux.doOnComplete(() -> log.info("[Perf] {}={}μs", stage, (System.nanoTime() - t0) / 1000));
        }
        long elapsed = (System.nanoTime() - t0) / 1000;
        log.info("[Perf] {}={}μs", stage, elapsed);
        return result;
    }
}
