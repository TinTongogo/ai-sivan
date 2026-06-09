package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.service.LeafExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 叶子执行器注册中心 — Registry 模式。
 * <p>
 * 新增叶子节点类型 = 新增 {@link LeafExecutor} 实现 + 自动注册。零修改。
 */
@Component
public class LeafExecutorRegistry {

    private final Map<String, LeafExecutor> executors;

    public LeafExecutorRegistry(List<LeafExecutor> executorList) {
        this.executors = executorList.stream()
                .collect(Collectors.toConcurrentMap(
                        LeafExecutor::supportedType,
                        Function.identity(),
                        (a, b) -> { throw new IllegalStateException("重复的叶子执行器: " + a.supportedType()); }
                ));
    }

    public LeafExecutor forType(String nodeType) {
        return executors.get(nodeType);
    }

    public void register(LeafExecutor executor) {
        executors.put(executor.supportedType(), executor);
    }
}
