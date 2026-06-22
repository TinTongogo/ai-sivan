package com.icusu.sivan.application.forest;

import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.infra.forest.execution.GoalExecutionService;
import com.icusu.sivan.infra.forest.execution.ProgressAggregator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 目标执行应用服务 — 包装 GoalExecutionService 与 ProgressAggregator，
 * 作为 web 层访问目标编排能力的唯一入口。
 */
@Service
public class ForestGoalAppService {

    private final GoalExecutionService goalExecutionService;
    private final ProgressAggregator progressAggregator;

    public ForestGoalAppService(GoalExecutionService goalExecutionService,
                                ProgressAggregator progressAggregator) {
        this.goalExecutionService = goalExecutionService;
        this.progressAggregator = progressAggregator;
    }

    /**
     * 执行目标树，返回事件流。
     */
    public Flux<ForestEvent> execute(Forest forest, ExecutableNode root, ExecutionContext ctx) {
        return goalExecutionService.execute(forest, root, ctx);
    }

    /**
     * 取消目标执行。
     */
    public boolean cancelExecution(UUID goalId) {
        return goalExecutionService.cancelExecution(goalId);
    }

    /**
     * 汇总执行进度。
     */
    public Progress aggregateProgress(TreeNode root) {
        return progressAggregator.aggregate(root);
    }
}
