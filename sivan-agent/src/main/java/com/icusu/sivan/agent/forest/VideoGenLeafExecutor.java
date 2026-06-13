package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.service.ModelParams;
import com.icusu.sivan.domain.forest.service.VideoGenCapability;
import com.icusu.sivan.domain.forest.service.VideoGenEvent;
import com.icusu.sivan.domain.forest.service.VideoPrompt;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 视频生成叶子执行器 — 处理 video_gen 节点类型（17-多模态 §2）。
 * <p>
 * 通过 {@link VideoGenCapability} 调用视频生成模型。
 * 节点 content 为文本提示词，metadata 可配置 size（分辨率）和 durationSec（时长）。
 */
@Component
public class VideoGenLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(VideoGenLeafExecutor.class);

    @Override
    public String supportedType() { return "video_gen"; }

    @Override
    public Flux<ForestEvent> execute(com.icusu.sivan.domain.forest.tree.TreeNode node, com.icusu.sivan.domain.forest.context.ExecutionContext ctx, com.icusu.sivan.domain.forest.service.EventSink sink) {
        String prompt = node instanceof ContentNode cn ? cn.content() : "";
        if (prompt.isBlank()) {
            log.warn("[VideoGen] 空提示词: nodeId={}", node.nodeId());
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), ""));
        }

        VideoGenCapability videoGen = ProviderFactory.getVideoGen();
        if (videoGen == null) {
            // 降级：用 LLM 描述视频场景
            return textFallback(node, ctx, prompt);
        }

        String size = "1920x1080";
        int durationSec = 10;
        if (node instanceof ContentNode cn) {
            Object rawSize = cn.metadata().get("size");
            if (rawSize instanceof String s) size = s;
            Object rawDur = cn.metadata().get("durationSec");
            if (rawDur instanceof Number n) durationSec = n.intValue();
        }

        log.info("[VideoGen] 开始生成: prompt={} size={} duration={}s", prompt, size, durationSec);

        return videoGen.generate(new VideoPrompt(prompt, size, durationSec), ModelParams.defaults())
                .concatMap(event -> switch (event) {
                    case VideoGenEvent.Progress p -> {
                        sink.emit(ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), "视频生成中: " + p.percent() + "%"));
                        yield Flux.<ForestEvent>empty();
                    }
                    case VideoGenEvent.Completed c -> {
                        String msg = "🎬 [视频已生成](" + c.url() + ")";
                        yield Flux.just(ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), msg));
                    }
                    case VideoGenEvent.Error e ->
                        Flux.just(ForestEvent.error(node.nodeId(), null,
                                ctx.accountId().toString(),
                                "视频生成失败: " + (e.cause() != null ? e.cause().getMessage() : "")));
                })
                .onErrorResume(e -> textFallback(node, ctx, prompt));
    }

    private Flux<ForestEvent> textFallback(TreeNode node, ExecutionContext ctx, String prompt) {
        log.info("[VideoGen] 降级为文本描述: prompt={}", prompt);
        return Flux.just(ForestEvent.detail(node.nodeId(), null,
                ctx.accountId().toString(),
                "视频生成服务暂不可用。以下是对视频场景的描述：\n" + prompt));
    }

    @Override
    public int maxRetries() { return 0; }
}
