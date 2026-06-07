package com.icusu.sivan.core.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 运行时消息接口。
 * role() + contents() 构成消息体，不包含序列化逻辑。
 */
public interface Msg {

    Role role();
    List<Content> contents();

    /** 获取首个指定类型的 Content。 */
    default <T extends Content> T first(Class<T> type) {
        return Content.firstOf(contents(), type);
    }

    /** 提取全部文本内容。 */
    default String text() {
        return Content.extractText(contents());
    }

    /** 提取全部思考内容。 */
    default String thinking() {
        return Content.extractThinking(contents());
    }

    /** 创建一条纯文本消息。 */
    static Msg of(Role role, String text) {
        return new DefaultMsg(role, List.of(new Content.Text(text)));
    }

    /** 创建一条多 Content 消息。 */
    static Msg of(Role role, List<Content> contents) {
        return new DefaultMsg(role, List.copyOf(contents));
    }

    /** 创建 Builder。 */
    static Builder builder() { return new Builder(); }

    class Builder {
        private Role role;
        private final List<Content> contents = new ArrayList<>();

        public Builder role(Role role) { this.role = role; return this; }
        public Builder add(Content content) { contents.add(content); return this; }
        public Builder text(String text) { contents.add(new Content.Text(Objects.requireNonNull(text))); return this; }
        public Builder thinking(String thinking, String sig) { contents.add(new Content.Thinking(thinking, sig)); return this; }
        public Builder toolCall(String id, String name, java.util.Map<String, Object> args) { contents.add(new Content.ToolCall(id, name, args)); return this; }
        public Builder toolResult(String id, boolean success, String content) { contents.add(new Content.ToolResult(id, success, content)); return this; }
        public Builder image(String mime, byte[] data) { contents.add(new Content.Image(mime, data)); return this; }
        public Builder audio(String mime, byte[] data, String transcript) { contents.add(new Content.Audio(mime, data, transcript)); return this; }
        public Msg build() { return new DefaultMsg(role, List.copyOf(contents)); }
    }
}

/** package-private: Message 的默认实现（用户只通过 factory 方法创建）。 */
record DefaultMsg(Role role, List<Content> contents) implements Msg {}
