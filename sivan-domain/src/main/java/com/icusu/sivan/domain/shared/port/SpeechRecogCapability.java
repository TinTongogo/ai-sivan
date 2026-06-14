package com.icusu.sivan.domain.shared.port;

import com.icusu.sivan.domain.forest.event.SpeechRecogEvent;
import com.icusu.sivan.domain.shared.port.Capability;
import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.forest.vo.SpeechRecogRequest;
import reactor.core.publisher.Flux;

/**
 * 语音识别（STT）能力。
 */
public interface SpeechRecogCapability extends Capability {
    Flux<SpeechRecogEvent> recognize(SpeechRecogRequest request, ModelParams params);
}

