package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.vo.ModelParams;
import com.icusu.sivan.domain.shared.port.SpeechRecogCapability;
import com.icusu.sivan.domain.forest.event.SpeechRecogEvent;
import com.icusu.sivan.domain.forest.vo.SpeechRecogRequest;
import reactor.core.publisher.Flux;

/**
 * STT 回显适配器 — 返回"语音识别暂不可用"提示。
 * 当实际 STT 服务未配置时作为兜底。
 */
public class EchoSttAdapter implements SpeechRecogCapability {
    @Override public String modelId() { return "echo-stt"; }

    @Override
    public Flux<SpeechRecogEvent> recognize(SpeechRecogRequest request, ModelParams params) {
        return Flux.just(new SpeechRecogEvent.Completed("[语音识别当前不可用]", "zh"));
    }
}
