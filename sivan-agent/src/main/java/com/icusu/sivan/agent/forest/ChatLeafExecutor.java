package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.infra.prompt.PromptAssembler;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 对话叶子执行器 — 处理纯对话的叶子节点（nodeType="message"）。
 * <p>
 * 与 {@link AgentLeafExecutor} 不同，ChatLeafExecutor 只调用 LLM 对话，
 * 不涉及工具调用和 ReAct 循环。适用于 CHAT 场景的简单问答。
 */
@Component
public class ChatLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatLeafExecutor.class);

    private final DefaultModelRouter modelRouter;
    private final PromptAssembler promptAssembler;

    public ChatLeafExecutor(DefaultModelRouter modelRouter, PromptAssembler promptAssembler) {
        this.modelRouter = modelRouter;
        this.promptAssembler = promptAssembler;
    }

    @Override
    public String supportedType() {
        return "message";
    }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        String content = node instanceof ContentNode cn ? cn.content() : "";
        if (content.isBlank()) {
            log.warn("[ChatLeaf] 空消息节点: nodeId={}", node.nodeId());
            return Flux.just(ForestEvent.detail(node.nodeId(), null, ctx.accountId().toString(), ""));
        }

        UUID accountId = ctx.accountId();
        Model model;
        try {
            model = modelRouter.getDefaultModel(accountId);
        } catch (Exception e) {
            log.error("[ChatLeaf] 获取模型失败: {}", e.getMessage());
            return Flux.just(ForestEvent.error(node.nodeId(), null, ctx.accountId().toString(),
                    "获取模型失败: " + e.getMessage()));
        }

        log.info("[ChatLeaf] 执行对话: nodeId={} contentLen={}", node.nodeId(), content.length());

        // 优先使用 PromptAssembler 构建消息
        List<Msg> messages;
        if (node instanceof ContentNode cn) {
            Object raw = cn.metadata().get("prebuiltMessages");
            if (raw instanceof List<?> list && !list.isEmpty()) {
                messages = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Msg m) messages.add(m);
                }
                messages.add(Msg.of(Role.USER, List.of(new Content.Text(content))));
            } else {
                // 无预构建消息 → 使用 PromptAssembler 装配
                messages = promptAssembler.assemble("CHAT", "default", content, null, null);
            }
        } else {
            messages = promptAssembler.assemble("CHAT", "default", content, null, null);
        }
        log.debug("[ChatLeaf] 消息数={} (含历史)", messages.size());

        return Flux.from(model.chat(messages, List.of(), Model.ModelParams.defaults()))
                .flatMap(response -> {
                    String text = response.msg().text();
                    sink.emit(ForestEvent.thinking(node.nodeId(), null, ctx.accountId().toString(),
                            response.msg().thinking() != null ? response.msg().thinking() : ""));
                    return Flux.just(ForestEvent.detail(node.nodeId(), null, ctx.accountId().toString(), text));
                });
    }

    @Override
    public int maxRetries() {
        return 1;
    }
}
