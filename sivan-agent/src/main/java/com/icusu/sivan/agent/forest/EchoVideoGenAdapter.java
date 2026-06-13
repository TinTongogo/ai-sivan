package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.service.ModelParams;
import com.icusu.sivan.domain.forest.service.VideoGenCapability;
import com.icusu.sivan.domain.forest.service.VideoGenEvent;
import com.icusu.sivan.domain.forest.service.VideoPrompt;
import reactor.core.publisher.Flux;

/**
 * 视频生成回显适配器 — 返回"视频生成暂不可用"提示。
 * 当实际视频生成服务未配置时作为兜底。
 */
public class EchoVideoGenAdapter implements VideoGenCapability {
    @Override public String modelId() { return "echo-video"; }

    @Override
    public Flux<VideoGenEvent> generate(VideoPrompt prompt, ModelParams params) {
        return Flux.just(new VideoGenEvent.Completed("", "none", 0));
    }
}
