package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.service.ModelParams;
import com.icusu.sivan.domain.forest.service.SpeechRecogCapability;
import com.icusu.sivan.domain.forest.service.SpeechRecogEvent;
import com.icusu.sivan.domain.forest.service.SpeechRecogRequest;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Base64;

/**
 * 语音识别叶子执行器 — 处理 speech_recog 节点类型（17-多模态 §2）。
 * <p>
 * 通过 {@link SpeechRecogCapability} 调用 STT 模型将语音转为文字。
 * 音频数据从节点 metadata 的 audioData（base64 编码）和 audioFormat 字段获取。
 */
@Component
public class SpeechRecogLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecogLeafExecutor.class);

    @Override
    public String supportedType() { return "speech_recog"; }

    @Override
    public Flux<ForestEvent> execute(com.icusu.sivan.domain.forest.tree.TreeNode node, com.icusu.sivan.domain.forest.context.ExecutionContext ctx, com.icusu.sivan.domain.forest.service.EventSink sink) {
        String prompt = node instanceof ContentNode cn ? cn.content() : "";
        String audioB64 = null;
        String audioFormat = "wav";
        if (node instanceof ContentNode cn) {
            Object rawData = cn.metadata().get("audioData");
            if (rawData instanceof String s) audioB64 = s;
            Object rawFmt = cn.metadata().get("audioFormat");
            if (rawFmt instanceof String s) audioFormat = s;
        }

        if (audioB64 == null || audioB64.isBlank()) {
            log.warn("[SpeechRecog] 无音频数据: nodeId={}", node.nodeId());
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), prompt));
        }

        SpeechRecogCapability stt = ProviderFactory.getSpeechRecog();
        if (stt == null) {
            log.warn("[SpeechRecog] STT 适配器未初始化");
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), prompt));
        }

        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(audioB64);
        } catch (Exception e) {
            return Flux.just(ForestEvent.error(node.nodeId(), null,
                    ctx.accountId().toString(), "音频数据解码失败"));
        }

        log.info("[SpeechRecog] 开始识别: audioLen={} format={}", audio.length, audioFormat);

        return stt.recognize(new SpeechRecogRequest(audio, audioFormat, "zh"), ModelParams.defaults())
                .concatMap(event -> switch (event) {
                    case SpeechRecogEvent.Chunk c ->
                        Flux.just(ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), c.text()));
                    case SpeechRecogEvent.Completed c ->
                        Flux.just(ForestEvent.detail(node.nodeId(), null,
                                ctx.accountId().toString(), c.fullText()));
                    case SpeechRecogEvent.Error e ->
                        Flux.just(ForestEvent.error(node.nodeId(), null,
                                ctx.accountId().toString(),
                                "语音识别失败: " + (e.cause() != null ? e.cause().getMessage() : "")));
                })
                .onErrorResume(e -> {
                    log.warn("[SpeechRecog] 识别异常: {}", e.getMessage());
                    return Flux.just(ForestEvent.error(node.nodeId(), null,
                            ctx.accountId().toString(), "语音识别失败: " + e.getMessage()));
                });
    }

    @Override
    public int maxRetries() { return 1; }
}
