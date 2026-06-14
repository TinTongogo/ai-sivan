package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.shared.port.Capability;
import com.icusu.sivan.domain.shared.port.ChatCapability;
import com.icusu.sivan.domain.shared.port.ImageGenCapability;
import com.icusu.sivan.domain.shared.port.Model;
import com.icusu.sivan.domain.shared.port.SpeechRecogCapability;
import com.icusu.sivan.domain.shared.port.SpeechSynthCapability;
import com.icusu.sivan.domain.shared.port.VideoGenCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 多模态路由器 — 按能力类型选择合适的 Model。
 * <p>
 * 设计文档 10.6 节：通过 {@link Model#as(Class)} 查找提供指定能力的模型。
 */
@Component
public class MultimodalRouter {

    private static final Logger log = LoggerFactory.getLogger(MultimodalRouter.class);

    private final ModelRegistry registry;

    public MultimodalRouter(ModelRegistry registry) {
        this.registry = registry;
    }

    /** 找个能聊天的模型。 */
    public ChatCapability chat() {
        return forCapability(ChatCapability.class);
    }

    /** 找个能生成图像的模型。 */
    public ImageGenCapability imageGen() {
        return forCapability(ImageGenCapability.class);
    }

    /** 找个能 TTS 的模型。 */
    public SpeechSynthCapability speechSynth() {
        List<SpeechSynthCapability> dedicated = findByType(SpeechSynthCapability.class);
        if (!dedicated.isEmpty()) return dedicated.getFirst();
        log.warn("[多模态] 无专用 TTS 模型可用");
        throw new CapabilityNotFoundException("SpeechSynthCapability");
    }

    /** 找个能语音识别的模型。 */
    public SpeechRecogCapability speechRecog() {
        return forCapability(SpeechRecogCapability.class);
    }

    /** 找个能生成视频的模型。 */
    public VideoGenCapability videoGen() {
        return forCapability(VideoGenCapability.class);
    }

    /** 按能力类型查找（通用方法）。 */
    public <T extends Capability> T forCapability(Class<T> type) {
        List<T> found = findByType(type);
        if (found.isEmpty()) {
            throw new CapabilityNotFoundException(type.getSimpleName());
        }
        return found.getFirst();
    }

    @SuppressWarnings("unchecked")
    private <T extends Capability> List<T> findByType(Class<T> type) {
        return registry.all().stream()
                .map(m -> {
                    if (m instanceof Model container) {
                        return container.as(type);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public static class CapabilityNotFoundException extends RuntimeException {
        public CapabilityNotFoundException(String capabilityName) {
            super("无可用模型支持该能力: " + capabilityName);
        }
    }
}
