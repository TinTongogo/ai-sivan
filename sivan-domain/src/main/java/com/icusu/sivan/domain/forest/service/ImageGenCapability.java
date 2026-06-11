package com.icusu.sivan.domain.forest.service;

import reactor.core.publisher.Flux;

/**
 * 图像生成能力。
 */
public interface ImageGenCapability extends Capability {
    Flux<ImageGenEvent> generate(ImagePrompt prompt, ModelParams params);
}


