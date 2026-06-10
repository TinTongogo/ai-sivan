package com.icusu.sivan.infra.memory.pattern;

import com.icusu.sivan.domain.memory.ExplorationState;
import com.icusu.sivan.domain.memory.IExplorationStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ε-greedy 探索决策器。
 * 控制本能模板匹配时是否跳过模板走 LLM 生成，避免 confirmation bias。
 */
@Slf4j
@Component
public class ExplorationDecider {

    static final double BASE_EXPLORATION_RATE = 0.10;
    static final int DECAY_STEP = 10;
    static final double MIN_EXPLORATION_RATE = 0.03;
    static final int COOLDOWN_CALLS = 3;

    private final IExplorationStateRepository repository;

    public ExplorationDecider(IExplorationStateRepository repository) {
        this.repository = repository;
    }

    public boolean shouldExplore(Object accountId, int templateCount) {
        UUID id;
        if (accountId instanceof UUID u) {
            id = u;
        } else {
            log.warn("非 UUID 类型 accountId: {}", accountId);
            return false;
        }

        ExplorationState state = repository.findById(id).orElse(new ExplorationState(id));
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

    public double getExplorationRate(Object accountId, int templateCount) {
        return computeRate(templateCount);
    }

    public void reset(Object accountId) {
        if (accountId instanceof UUID id) {
            repository.deleteById(id);
        }
    }

    double computeRate(int templateCount) {
        double rate = BASE_EXPLORATION_RATE - (templateCount / DECAY_STEP) * 0.01;
        return Math.max(rate, MIN_EXPLORATION_RATE);
    }
}
