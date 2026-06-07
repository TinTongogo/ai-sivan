package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import com.icusu.sivan.memory.pattern.ExplorationDecider;
import com.icusu.sivan.memory.pattern.FeatureExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 执行路径解析器。在请求入口处前置执行，决定执行路径来源：
 * <ul>
 *   <li>命中本能模板且未触发探索 → 直接返回模板中的执行路径（零 LLM 调用）</li>
 *   <li>命中本能模板但触发探索 → 返回 LLM classify 路径</li>
 *   <li>未命中模板 → 返回 LLM classify 路径</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionPathResolver {

    private final FeatureExtractor featureExtractor;
    private final InstinctPatternService instinctPatternService;
    private final ExplorationDecider explorationDecider;
    private final IInstinctPatternRepository patternRepository;

    /**
     * 解析任务描述的执行路径。
     *
     * @param taskDescription 用户任务描述
     * @param accountId       账户 ID
     * @return 路径解析结果
     */
    public ExecutionPathResult resolve(String taskDescription, UUID accountId) {
        // 1. 特征提取（L1 启发式）
        TaskFeatures features = featureExtractor.extractHeuristic(taskDescription);

        // 2. 本能模板匹配
        Optional<InstinctPattern> matched = instinctPatternService.match(features, accountId);
        if (matched.isEmpty()) {
            log.debug("执行路径解析: 无匹配模板, accountId={}", accountId);
            return ExecutionPathResult.noMatch();
        }

        InstinctPattern pattern = matched.get();

        // 3. 探索决策
        int templateCount = patternRepository.findActiveByAccount(accountId).size();
        boolean exploring = explorationDecider.shouldExplore(accountId, templateCount);

        if (exploring) {
            log.info("执行路径解析: 命中模板但触发探索 (patternId={}, templateCount={})",
                    pattern.getPatternId(), templateCount);
        } else {
            log.info("执行路径解析: 命中模板直接使用 (patternId={}, shape={})",
                    pattern.getPatternId(), pattern.getExecutionMode());
        }

        ExecutionPathResult.InstinctPatternWithPath carrier =
                new ExecutionPathResult.InstinctPatternWithPath(
                        pattern.toExecutionPath(),
                        pattern.getPatternId(),
                        exploring
                );

        return ExecutionPathResult.templateMatch(carrier);
    }
}
