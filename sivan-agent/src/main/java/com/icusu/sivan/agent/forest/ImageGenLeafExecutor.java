package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.event.ImageGenEvent;
import com.icusu.sivan.domain.shared.port.ImageGenCapability;
import com.icusu.sivan.domain.forest.vo.ImagePrompt;
import com.icusu.sivan.domain.forest.port.LeafExecutor;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 图像生成叶子执行器 — 处理 image_gen 节点类型。
 * <p>
 * 通过 {@link ImageGenCapability} 调用 DALL-E 等图像生成模型。
 */
@Component
public class ImageGenLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(ImageGenLeafExecutor.class);

    @Override
    public String supportedType() { return "image_gen"; }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        String prompt = node.content();
        if (prompt.isBlank()) {
            log.warn("[ImageGen] 空提示词: nodeId={}", node.nodeId());
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), ""));
        }

        ImageGenCapability imageGen = ProviderFactory.getImageGen();
        if (imageGen == null) {
            log.warn("[ImageGen] DALL-E 适配器未初始化");
            return Flux.just(ForestEvent.error(node.nodeId(), null,
                    ctx.accountId().toString(), "图像生成服务不可用"));
        }

        log.info("[ImageGen] 开始生成: prompt={}", prompt);

        return imageGen.generate(ImagePrompt.of(prompt), ModelParams.defaults())
                .map(event -> switch (event) {
                    case ImageGenEvent.Completed c -> {
                        String msg = "![生成图像](" + c.url() + ")";
                        sink.emit(ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), msg));
                        yield ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), msg);
                    }
                    case ImageGenEvent.Error e ->
                            ForestEvent.error(node.nodeId(), null,
                                    ctx.accountId().toString(),
                                    "图像生成失败: " + e.cause().getMessage());
                    default -> ForestEvent.detail(node.nodeId(), null,
                            ctx.accountId().toString(), "");
                });
    }
}
