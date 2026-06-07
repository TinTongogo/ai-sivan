package com.icusu.sivan.core.message;

import java.util.List;
import java.util.Map;

public sealed interface Content permits
        Content.Text, Content.Thinking, Content.ToolCall, Content.ToolResult, Content.Image, Content.Audio {

    record Text(String text) implements Content {}
    record Thinking(String thinking, String signature) implements Content {}
    record ToolCall(String id, String name, Map<String, Object> args) implements Content {}
    record ToolResult(String id, boolean success, String content) implements Content {}
    record Image(String mimeType, byte[] data) implements Content {}
    record Audio(String mimeType, byte[] data, String transcript) implements Content {}

    /** 获取首个指定类型的 Content。 */
    static <T extends Content> T firstOf(List<Content> contents, Class<T> type) {
        for (Content c : contents) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    /** 提取所有文本内容拼接为单字符串。 */
    static String extractText(List<Content> contents) {
        var sb = new StringBuilder();
        for (Content c : contents) {
            if (c instanceof Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    /** 提取所有思考内容拼接为单字符串。 */
    static String extractThinking(List<Content> contents) {
        var sb = new StringBuilder();
        for (Content c : contents) {
            if (c instanceof Thinking t) sb.append(t.thinking());
        }
        return sb.toString();
    }
}
