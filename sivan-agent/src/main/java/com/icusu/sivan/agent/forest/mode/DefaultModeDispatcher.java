package com.icusu.sivan.agent.forest.mode;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.Continuation;
import com.icusu.sivan.domain.forest.port.ModeDispatcher;
import com.icusu.sivan.domain.forest.port.ModeStrategy;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 默认编排模式分派器 — Registry 模式，无 switch。
 * <p>
 * 新增 Mode：新增 {@link ModeStrategy} 实现 + {@code @Component}，无需修改此类。
 */
@Component
public class DefaultModeDispatcher implements ModeDispatcher {

    private final Map<Mode, ModeStrategy> strategies;

    public DefaultModeDispatcher(List<ModeStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ModeStrategy::supportedMode, Function.identity()));
    }

    @Override
    public Flux<ForestEvent> dispatch(
            ExecutableNode node,
            ExecutionContext ctx,
            int depth,
            Continuation next
    ) {
        Objects.requireNonNull(next, "continuation must not be null");
        ModeStrategy strategy = strategies.get(node.mode());
        if (strategy == null) {
            return Flux.empty();
        }
        return strategy.execute(node, ctx, depth, next);
    }
}
