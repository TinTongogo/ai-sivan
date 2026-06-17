package com.icusu.sivan.agent.forest;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.port.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 图像分析叶子执行器 — 处理 image_analysis 节点类型（17-多模态 §2）。
 * <p>
 * 使用支持 VISION 的模型分析图片内容。
 * 图片 URL 从节点 metadata 的 imageUrl 字段获取。
 */
@Component
public class ImageAnalysisLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisLeafExecutor.class);

    private final DefaultModelRouter modelRouter;

    public ImageAnalysisLeafExecutor(DefaultModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public String supportedType() {
        return "image_analysis";
    }

    @Override
    public Flux<ForestEvent> execute(com.icusu.sivan.domain.forest.tree.TreeNode node, com.icusu.sivan.domain.forest.context.ExecutionContext ctx, com.icusu.sivan.domain.shared.port.EventSink sink) {
        String prompt = node.content();
        String rawUrl = node.metadataString("imageUrl");
        String imageUrl = rawUrl instanceof String s ? s : null;

        if (prompt.isBlank() && imageUrl == null) {
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), ""));
        }

        UUID accountId = ctx.accountId();
        log.info("[ImageAnalysis] 分析图片: prompt={} imageUrl={}", prompt, imageUrl);

        return Flux.defer(() -> {
            Model model;
            try {
                model = modelRouter.getDefaultModel(accountId);
            } catch (Exception e) {
                return Flux.just(ForestEvent.error(node.nodeId(), null,
                        accountId.toString(), "获取模型失败: " + e.getMessage()));
            }

            List<Content> contents = new ArrayList<>();
            String analysisPrompt = prompt != null && !prompt.isBlank() ? prompt : "请分析这张图片";
            if (imageUrl != null && !imageUrl.isBlank()) {
                analysisPrompt = "图片地址: " + imageUrl + "\n" + analysisPrompt;
            }
            contents.add(new Content.Text(analysisPrompt));
            List<Msg> messages = List.of(Msg.of(Role.USER, contents));

            return model.stream(messages, List.of(), Model.ModelParams.defaults())
                    .concatMap(chunk -> {
                        if (!chunk.content().isEmpty()) {
                            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                                    accountId.toString(), chunk.content()));
                        }
                        return Flux.empty();
                    })
                    .onErrorResume(e -> {
                        log.warn("[ImageAnalysis] 分析失败: {}", e.getMessage());
                        // 降级：返回错误提示
                        return Flux.just(ForestEvent.error(node.nodeId(), null,
                                accountId.toString(), "图片分析失败: " + e.getMessage()));
                    });
        });
    }

    @Override
    public int maxRetries() {
        return 1;
    }
}
