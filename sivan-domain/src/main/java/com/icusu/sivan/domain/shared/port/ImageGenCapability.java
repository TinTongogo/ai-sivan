package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.ImageGenEvent;
import com.icusu.sivan.domain.forest.vo.ImagePrompt;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import reactor.core.publisher.Flux;

/**
 * 图像生成能力。
 */
public interface ImageGenCapability extends Capability {
    Flux<ImageGenEvent> generate(ImagePrompt prompt, ModelParams params);
}


