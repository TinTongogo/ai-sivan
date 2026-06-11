package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.service.Capability;
import reactor.core.publisher.Flux;

/**
 * 语音识别（STT）能力。
 */
public interface SpeechRecogCapability extends Capability {
    Flux<SpeechRecogEvent> recognize(SpeechRecogRequest request, ModelParams params);
}

