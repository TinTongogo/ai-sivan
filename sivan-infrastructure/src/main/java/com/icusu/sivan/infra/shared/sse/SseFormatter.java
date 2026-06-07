package com.icusu.sivan.infra.shared.sse;

import com.icusu.sivan.common.util.JsonUtil;

/**
 * SSE 事件格式化工具。
 * 构建流式对话的 response / thinking / meta 三类事件 JSON。
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

    /** 构建 meta 事件 JSON 字符串（含消息 ID 和 chain）。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs, String messageId) {
        return buildMetaEvent(modelName, totalTokens, durationMs, thinkingMs, null, messageId, null);
    }

    /** 构建 meta 事件 JSON 字符串（含全部可选字段）。 */
    public static String buildMetaEvent(String modelName, Integer totalTokens,
                                         int durationMs, int thinkingMs, String messageId, String chain) {
        return buildMetaEvent(modelName, totalTokens, durationMs, thinkingMs, null, messageId, chain);
    }

    /** 构建 meta 事件 JSON 字符串（含 thinkingTokens）。 */
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
}
