package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.SpeechSynthEvent;
import com.icusu.sivan.domain.shared.port.Capability;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.SpeechSynthRequest;
import reactor.core.publisher.Flux;

/**
 * 语音合成（TTS）能力。
 */
public interface SpeechSynthCapability extends Capability {
    Flux<SpeechSynthEvent> synthesize(SpeechSynthRequest request, ModelParams params);
}


