package com.icusu.sivan.domain.orchestration;

import java.util.List;
import java.util.Map;

/**
 * 结构化阶段输出。替代纯字符串传递，每个阶段的输出包含结构化信息和元数据。
 * content 是 LLM 消费的正文，artifacts 是系统消费的结构化键值对，summary 是上下文注入用的摘要。
 */
public record PhaseOutput(
        /** 阶段输出的主要文本内容。 */
        String content,
        /** 结构化键值对输出（供下游精确引用）。 */
        Map<String, String> artifacts,
        /** 本阶段的关键结论摘要（不超过 200 字）。 */
        String summary,
        /** 本阶段使用的 Agent 清单。 */
        List<String> agents,
        /** 本阶段生成的中间产物引用列表。 */
        List<ArtifactRef> artifactRefs,
        /** 输出置信度 0~1（仅 CONSENSUS 模式有意义）。 */
        Double confidence
) {

    public PhaseOutput(String content) {
        this(content, null, null, null, null, null);
    }

    public String summary() {
        return summary != null ? summary : "";
    }

    public Map<String, String> artifacts() {
        return artifacts != null ? artifacts : Map.of();
    }

    public List<String> agents() {
        return agents != null ? agents : List.of();
    }

    public List<ArtifactRef> artifactRefs() {
        return artifactRefs != null ? artifactRefs : List.of();
    }
}
