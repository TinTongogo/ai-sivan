package com.icusu.sivan.domain.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 模型能力枚举。
 * code 用于存储/传输，label 用于前端展示。
 */
public enum ModelCapability {

    VISION("vision", "图片理解"),
    AUDIO("audio", "音频理解"),
    TOOL_USE("tool_use", "工具调用"),
    STREAMING("streaming", "流式输出"),
    THINKING("thinking", "深度思考"),
    REASONING_EFFORT("reasoning_effort", "推理力度"),
    SYSTEM_PROMPT("system_prompt", "系统提示词"),
    JSON_MODE("json_mode", "JSON 模式"),
    BATCH("batch", "批量处理"),
    IMAGE_GEN("image_gen", "图像生成"),
    SPEECH_SYNTH("speech_synth", "语音合成"),
    SPEECH_RECOG("speech_recog", "语音识别"),
    VIDEO_GEN("video_gen", "视频生成"),
    MULTIMODAL_EMBED("multimodal_embed", "多模态向量化");

    private final String code;
    private final String label;

    ModelCapability(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }

    public String getLabel() { return label; }

    /** 按 code 查找，不区分大小写。 */
    public static ModelCapability fromCode(String code) {
        if (code == null) return null;
        for (ModelCapability c : values()) {
            if (c.code.equalsIgnoreCase(code.trim())) return c;
        }
        return null;
    }

    /** 将逗号分隔的能力字符串解析为 Set。 */
    public static Set<ModelCapability> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) return EnumSet.noneOf(ModelCapability.class);
        Set<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
        for (String part : capabilities.split(",")) {
            ModelCapability cap = fromCode(part.trim());
            if (cap != null) result.add(cap);
        }
        return result;
    }

    /** 将能力集合序列化为逗号分隔字符串。 */
    public static String serialize(Set<ModelCapability> caps) {
        return caps.stream().map(ModelCapability::getCode).collect(Collectors.joining(","));
    }
}
