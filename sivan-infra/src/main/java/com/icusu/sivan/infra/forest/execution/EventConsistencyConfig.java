package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.event.GoalTreeCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 领域事件事务保证配置（09-持久化与恢复 §4.5）。
 * <p>
 * 默认同步模式：发布者和订阅者在同一个事务中。
 * 异步订阅者使用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，
 * 事务已提交后执行，失败不影响主流程。
 */
@Configuration
public class EventConsistencyConfig {

    private static final Logger log = LoggerFactory.getLogger(EventConsistencyConfig.class);

    /**
     * GoalTree 完成后的异步订阅者。
     * 事务提交后才执行，失败不影响主流程。
     */
    @Component
    static class GoalTreeCompletionSubscriber {

        @Async
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onGoalTreeCompleted(GoalTreeCompleted event) {
            log.info("GoalTree 完成事件: goalId={} success={} conversationId={}",
                    event.rootNodeId(), event.success(), event.conversationId());
            // 可在未来扩展：通知用户、触发后续流程等
        }
    }
}
