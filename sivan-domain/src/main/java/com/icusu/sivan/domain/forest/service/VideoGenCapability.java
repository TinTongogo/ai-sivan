package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.service.Capability;
import reactor.core.publisher.Flux;

/**
 * 视频生成能力。
 */
public interface VideoGenCapability extends Capability {
    Flux<VideoGenEvent> generate(VideoPrompt prompt, ModelParams params);
}


