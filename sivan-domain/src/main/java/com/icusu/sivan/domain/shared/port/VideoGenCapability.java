package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.VideoGenEvent;
import com.icusu.sivan.domain.shared.port.Capability;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.VideoPrompt;
import reactor.core.publisher.Flux;

/**
 * 视频生成能力。
 */
public interface VideoGenCapability extends Capability {
    Flux<VideoGenEvent> generate(VideoPrompt prompt, ModelParams params);
}


