package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.EventSink;
import com.icusu.sivan.domain.forest.service.LeafExecutor;
import com.icusu.sivan.domain.forest.service.ModelParams;
import com.icusu.sivan.domain.forest.service.SpeechSynthCapability;
import com.icusu.sivan.domain.forest.service.SpeechSynthEvent;
import com.icusu.sivan.domain.forest.service.SpeechSynthRequest;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 语音合成叶子执行器 — 处理 speech_synth 节点类型。
 * <p>
 * 收集所有音频块，完成后 base64 编码为 data URL 传递给前端。
 * 前端检测到 {@code [audio:data:...]} 格式时渲染 {@code <audio>} 播放器。
 */
@Component
public class SpeechSynthLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpeechSynthLeafExecutor.class);

    private static final int MAX_AUDIO_BYTES = 10_000_000; // 10MB 上限

    @Override
    public String supportedType() { return "speech_synth"; }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        String text = node instanceof ContentNode cn ? cn.content() : "";
        if (text.isBlank()) {
            log.warn("[TTS] 空文本: nodeId={}", node.nodeId());
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), ""));
        }

        SpeechSynthCapability speechSynth = ProviderFactory.getSpeechSynth();
        if (speechSynth == null) {
            log.warn("[TTS] TTS 适配器未初始化");
            return Flux.just(ForestEvent.error(node.nodeId(), null,
                    ctx.accountId().toString(), "语音合成服务不可用"));
        }

        log.info("[TTS] 开始合成: textLen={}", text.length());

        List<byte[]> audioChunks = new ArrayList<>();
        String[] formatHolder = {"mp3"};

        return speechSynth.synthesize(SpeechSynthRequest.of(text), ModelParams.defaults())
                .concatMap(event -> {
                    switch (event) {
                        case SpeechSynthEvent.AudioChunk a -> {
                            formatHolder[0] = a.format();
                            audioChunks.add(a.audio());
                            long total = audioChunks.stream().mapToLong(b -> b.length).sum();
                            if (total > MAX_AUDIO_BYTES) {
                                return Flux.error(new RuntimeException("TTS 音频超过 10MB 限制"));
                            }
                            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                                    ctx.accountId().toString(),
                                    "[音频合成中 " + total / 1024 + "KB]"));
                        }
                        case SpeechSynthEvent.Completed c -> {
                            // 合并且 base64 编码
                            byte[] allBytes = merge(audioChunks);
                            String b64 = Base64.getEncoder().encodeToString(allBytes);
                            String dataUrl = "data:audio/" + formatHolder[0] + ";base64," + b64;
                            log.info("[TTS] 合成完成: totalBytes={}", allBytes.length);
                            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                                    ctx.accountId().toString(), "[audio:" + dataUrl + "]"));
                        }
                        case SpeechSynthEvent.Error e ->
                            Flux.just(ForestEvent.error(node.nodeId(), null,
                                    ctx.accountId().toString(),
                                    "语音合成失败: " + e.cause().getMessage()));
                    }
                    // 实际上不会到达这里，但编译器需要
                    return Flux.empty();
                });
    }

    private static byte[] merge(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
