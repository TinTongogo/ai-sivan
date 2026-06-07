package com.icusu.sivan.memory.pattern;

import com.icusu.sivan.domain.memory.ExplorationState;
import com.icusu.sivan.domain.memory.IExplorationStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ε-greedy 探索决策器。
 * 控制本能模板匹配时是否跳过模板走 LLM 生成，避免 confirmation bias。
 *
 * <p>探索率公式：base 10%，每 10 个模板 -1%，保底 3%。
 * 状态持久化到 exploration_state 表，重启后不丢失。
 */
@Slf4j
@Component
public class ExplorationDecider {

    /** 基础探索率 */
    static final double BASE_EXPLORATION_RATE = 0.10;

    /** 每多少模板衰减 1% */
    static final int DECAY_STEP = 10;

    /** 保底探索率 */
    static final double MIN_EXPLORATION_RATE = 0.03;

    /** 每次探索后冷却调用次数（防连续探索） */
    static final int COOLDOWN_CALLS = 3;

    private final IExplorationStateRepository repository;

    public ExplorationDecider(IExplorationStateRepository repository) {
        this.repository = repository;
    }

    /**
     * 判定是否应该探索（走 LLM 而非模板）。
     *
     * @param accountId      账户标识
     * @param templateCount  该账户当前已激活模板数
     * @return true=走 LLM 探索，false=走模板匹配
     */
    public boolean shouldExplore(Object accountId, int templateCount) {
        UUID id;
        if (accountId instanceof UUID u) {
            id = u;
        } else {
            log.warn("非 UUID 类型 accountId: {}", accountId);
            return false;
        }

        ExplorationState state = repository.findById(id).orElse(
                new ExplorationState(id));
        state.setCallCount(state.getCallCount() + 1);

        double rate = computeRate(templateCount);
        boolean explore = ThreadLocalRandom.current().nextDouble() < rate;

        if (explore) {
            state.setLastExplorationCall(state.getCallCount());
        }
        repository.save(state);

        if (explore) {
            log.debug("探索决策: accountId={}, templateCount={}, rate={}, 决策=探索(LLM)",
                    accountId, templateCount, String.format("%.2f", rate));
        } else {
            log.trace("探索决策: accountId={}, templateCount={}, rate={}, 决策=利用(模板)",
                    accountId, templateCount, String.format("%.2f", rate));
        }
        return explore;
    }

    /**
     * 获取当前账户的探索率。
     */
    public double getExplorationRate(Object accountId, int templateCount) {
        return computeRate(templateCount);
    }

    /**
     * 重置账户计数器（用于测试）。
     */
    public void reset(Object accountId) {
        if (accountId instanceof UUID id) {
            repository.deleteById(id);
        }
    }

    /**
     * 计算探索率。
     */
    double computeRate(int templateCount) {
        double rate = BASE_EXPLORATION_RATE - (templateCount / DECAY_STEP) * 0.01;
        return Math.max(rate, MIN_EXPLORATION_RATE);
    }
}
