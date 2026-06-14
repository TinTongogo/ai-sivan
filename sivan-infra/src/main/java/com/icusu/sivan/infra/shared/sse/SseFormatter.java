package com.icusu.sivan.infra.shared.sse;

import com.icusu.sivan.common.util.JsonUtil;

/**
 * SSE 事件格式化工具。
 * 构建流式对话的 response / thinking / meta 三类事件 JSON。
 * V2 新增 phase_start / phase_end / progress 编排事件。
 */
public class SseFormatter {

    private SseFormatter() {}

    /** 构建文本事件：{"type":"response|thinking","content":"..."} */
    public static String toJsonEvent(String type, String content) {
        return new StringBuilder()
                .append("{\"type\":\"").append(JsonUtil.escapeJson(type))
                .append("\",\"content\":\"").append(JsonUtil.escapeJson(content))
                .append("\"}")
                .toString();
    }

    /** 向 JSON builder 追加字符串字段：, "key":"value" */
    public static StringBuilder appendJsonStr(StringBuilder json, String key, String value) {
        return json.append(",\"").append(JsonUtil.escapeJson(key)).append("\":\"")
                .append(JsonUtil.escapeJson(value)).append("\"");
    }

    /** 向 JSON builder 追加数字字段：, "key":123 */
    public static StringBuilder appendJsonNum(StringBuilder json, String key, long value) {
        return json.append(",\"").append(JsonUtil.escapeJson(key)).append("\":").append(value);
    }

    /** 构建 meta 事件 JSON 字符串。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs) {
        return buildMetaEvent(modelName, totalTokens, durationMs, thinkingMs, null, null, null);
    }

    /** 构建 meta 事件 JSON 字符串（含 messageId）。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs, String messageId) {
        return buildMetaEvent(modelName, totalTokens, durationMs, thinkingMs, null, messageId, null);
    }

    /** 构建 meta 事件 JSON 字符串（含 messageId + chain）。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs, String messageId, String chain) {
        return buildMetaEvent(modelName, totalTokens, durationMs, thinkingMs, null, messageId, chain);
    }

    /** 构建 meta 事件 JSON 字符串（含全部可选字段）。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs, Integer thinkingTokens,
                                         String messageId, String chain) {
        StringBuilder meta = new StringBuilder("{\"type\":\"meta\"");
        appendJsonStr(meta, "model", modelName);
        if (totalTokens != null) {
            appendJsonNum(meta, "totalTokens", totalTokens);
        }
        appendJsonNum(meta, "durationMs", durationMs);
        if (thinkingMs > 0) appendJsonNum(meta, "thinkingDurationMs", thinkingMs);
        if (thinkingTokens != null && thinkingTokens > 0) appendJsonNum(meta, "thinkingTokens", thinkingTokens);
        if (messageId != null) appendJsonStr(meta, "messageId", messageId);
        if (chain != null) appendJsonStr(meta, "chain", chain);
        meta.append("}");
        return meta.toString();
    }

    // ================================================================
    // V2 编排事件
    // ================================================================

    /**
     * 构建 phase_start 事件：{"type":"phase_start","phase":"...","agent":"...","mode":"...","phaseIndex":N,"totalPhases":N}
     *
     * @param phase       阶段名称
     * @param agent       执行 AI 名称，叶子阶段提供
     * @param mode        编排模式，非叶子阶段提供
     * @param phaseIndex  阶段序号（从 0 开始）
     * @param totalPhases 总阶段数
     */
    public static String buildPhaseStartEvent(String phase, String agent, String mode,
                                               int phaseIndex, int totalPhases) {
        StringBuilder json = new StringBuilder("{\"type\":\"phase_start\"");
        appendJsonStr(json, "phase", phase);
        if (agent != null && !agent.isBlank()) appendJsonStr(json, "agent", agent);
        if (mode != null && !mode.isBlank()) appendJsonStr(json, "mode", mode);
        appendJsonNum(json, "phaseIndex", phaseIndex);
        appendJsonNum(json, "totalPhases", totalPhases);
        json.append("}");
        return json.toString();
    }

    /**
     * 构建 phase_end 事件：{"type":"phase_end","phase":"...","agent":"...","tokens":N,"durationMs":N,"model":"..."}
     */
    public static String buildPhaseEndEvent(String phase, String agent,
                                             Integer tokens, Integer durationMs, String model) {
        StringBuilder json = new StringBuilder("{\"type\":\"phase_end\"");
        appendJsonStr(json, "phase", phase);
        if (agent != null && !agent.isBlank()) appendJsonStr(json, "agent", agent);
        if (tokens != null) appendJsonNum(json, "tokens", tokens);
        if (durationMs != null) appendJsonNum(json, "durationMs", durationMs);
        if (model != null && !model.isBlank()) appendJsonStr(json, "model", model);
        json.append("}");
        return json.toString();
    }

    /**
     * 构建 progress 事件：{"type":"progress","data":{...}}
     * data 为完整的 ProgressState JSON 字符串（已序列化）。
     */
    public static String buildProgressEvent(String progressStateJson) {
        return "{\"type\":\"progress\",\"data\":" + progressStateJson + "}";
    }

    /**
     * 构建 branch_decision 事件：{"type":"branch_decision","chosen":"...","skipped":["..."],"reason":"..."}
     */
    public static String buildBranchDecisionEvent(String chosen, java.util.List<String> skipped, String reason) {
        StringBuilder json = new StringBuilder("{\"type\":\"branch_decision\"");
        appendJsonStr(json, "chosen", chosen);
        if (skipped != null && !skipped.isEmpty()) {
            json.append(",\"skipped\":[");
            for (int i = 0; i < skipped.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(JsonUtil.escapeJson(skipped.get(i))).append("\"");
            }
            json.append("]");
        }
        if (reason != null && !reason.isBlank()) appendJsonStr(json, "reason", reason);
        json.append("}");
        return json.toString();
    }

    /** 构建 hitl_resume 事件：{"type":"hitl_resume","nodeId":"...","reason":"..."} */
    public static String buildHitlResumeEvent(String nodeId, String reason) {
        StringBuilder json = new StringBuilder("{\"type\":\"hitl_resume\"");
        appendJsonStr(json, "nodeId", nodeId);
        if (reason != null && !reason.isBlank()) appendJsonStr(json, "reason", reason);
        json.append("}");
        return json.toString();
    }

    /** 构建 hitl_reject 事件：{"type":"hitl_reject","nodeId":"...","reason":"..."} */
    public static String buildHitlRejectEvent(String nodeId, String reason) {
        StringBuilder json = new StringBuilder("{\"type\":\"hitl_reject\"");
        appendJsonStr(json, "nodeId", nodeId);
        if (reason != null && !reason.isBlank()) appendJsonStr(json, "reason", reason);
        json.append("}");
        return json.toString();
    }

    /**
     * 构建模板匹配事件：{"type":"match_template","step":"match_template","message":"..."}
     */
    public static String buildMatchTemplateEvent(String message) {
        return "{\"type\":\"match_template\",\"step\":\"match_template\",\"message\":\""
                + JsonUtil.escapeJson(message) + "\"}";
    }
}
