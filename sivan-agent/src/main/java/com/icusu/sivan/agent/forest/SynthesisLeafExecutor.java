package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.SynthesisNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 合成叶子执行器 — 为 CONSENSUS 模式提供 LLM 多视角合成。
 * <p>
 * 读取 {@link SynthesisNode#content()} 作为多路并行输出的合并文本，
 * 调用 LLM 生成结构化统一报告，发射 {@link ForestEvent} 事件。
 */
@Component
public class SynthesisLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(SynthesisLeafExecutor.class);

    private static final int MAX_INPUT_CHARS = 15000;

    private final DefaultModelRouter modelRouter;

    public SynthesisLeafExecutor(DefaultModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public String supportedType() {
        return "synthesis";
    }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        UUID accountId = ctx.accountId();
        String input = node instanceof ContentNode c ? c.content() : "";
        String nodeId = node.nodeId();

        if (input.isBlank()) {
            log.debug("[Synthesis] 空输入，跳过合成");
            return Flux.just(ForestEvent.detail(nodeId, null, accountId.toString(), ""));
        }

        log.info("[Synthesis] 开始合成: nodeId={} 输入长度={}", nodeId, input.length());

        return synthesize(input, accountId)
                .flatMapMany(result -> {
                    log.info("[Synthesis] 合成完成: {} 字符", result.length());
                    ForestEvent event = ForestEvent.detail(nodeId, null, accountId.toString(), result);
                    return Flux.just(event);
                })
                .onErrorResume(e -> {
                    log.error("[Synthesis] LLM 合成失败: {}", e.getMessage());
                    return Flux.just(ForestEvent.detail(nodeId, null, accountId.toString(),
                            "合成失败: " + e.getMessage()));
                });
    }

    @Override
    public int maxRetries() {
        return 1;
    }

    // =====================================================================
    // LLM 合成
    // =====================================================================

    private Mono<String> synthesize(String combinedOutput, UUID accountId) {
        Model model;
        try {
            model = modelRouter.getDefaultModel(accountId);
        } catch (Exception e) {
            log.warn("[Synthesis] 获取模型失败: {}", e.getMessage());
            return Mono.just(combinedOutput);
        }

        List<Msg> messages = List.of(
                Msg.of(Role.SYSTEM, """
                        你是多视角分析合成器。下面有多位专家对同一问题的分析结果。
                        请综合各专家意见，输出一份结构化的统一报告，包含：
                        1. 各视角的关键发现
                        2. 一致结论
                        3. 存在的分歧（如果有）
                        4. 综合建议"""),
                Msg.of(Role.USER, "各专家分析结果:\n" + truncate(combinedOutput, MAX_INPUT_CHARS))
        );

        return model.chat(messages, List.of(), Model.ModelParams.defaults().withTemperature(0.3))
                .map(response -> response.msg().text());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
