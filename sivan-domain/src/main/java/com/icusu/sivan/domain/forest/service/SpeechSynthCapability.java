package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.service.Capability;
import reactor.core.publisher.Flux;

/**
 * 语音合成（TTS）能力。
 */
public interface SpeechSynthCapability extends Capability {
    Flux<SpeechSynthEvent> synthesize(SpeechSynthRequest request, ModelParams params);
}


