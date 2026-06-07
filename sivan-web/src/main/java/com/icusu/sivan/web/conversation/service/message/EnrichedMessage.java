package com.icusu.sivan.web.conversation.service.message;

import com.icusu.sivan.domain.conversation.Message;
import lombok.Getter;

import java.util.List;

/**
 * 富化后的消息中间对象，携带原始消息及各维度注入的附加内容。
 */
@Getter
public class EnrichedMessage {

    private final Message original;
    private final StringBuilder contentBuilder;
    private List<String> imageRefs;
    private boolean imagesAttached;
    private List<String> audioRefs;
    private boolean audiosAttached;

    public EnrichedMessage(Message original) {
        this.original = original;
        this.contentBuilder = new StringBuilder(original.getContent() != null ? original.getContent() : "");
    }

    /** 在内容前插入文本。 */
    public void prependContent(String text) {
        contentBuilder.insert(0, text);
    }

    /** 在内容后追加文本。 */
    public void appendContent(String text) {
        contentBuilder.append(text);
    }

    /** 设置该消息需要附加的图片引用列表。 */
    public void setImageRefs(List<String> imageRefs) {
        this.imageRefs = imageRefs;
        this.imagesAttached = imageRefs != null && !imageRefs.isEmpty();
    }

    /** 设置该消息需要附加的音频引用列表。 */
    public void setAudioRefs(List<String> audioRefs) {
        this.audioRefs = audioRefs;
        this.audiosAttached = audioRefs != null && !audioRefs.isEmpty();
    }

    /** 获取拼接后的完整内容。 */
    public String getEnrichedContent() {
        return contentBuilder.toString();
    }
}
