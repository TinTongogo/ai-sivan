package com.icusu.sivan.domain.context;

/**
 * 上下文分段，表示 Epoch 缓存感知的单一输出片段。
 * <p>
 * 每个 Segment 对应一个 Epoch 的输出内容，
 * {@link #isCacheBreakpoint()} 标记该段后可设置 LLM Prompt 缓存（cache_control）。
 */
public class ContextSegment {

    private final Epoch epoch;
    private final String content;
    private final boolean cacheBreakpoint;

    public ContextSegment(Epoch epoch, String content, boolean cacheBreakpoint) {
        this.epoch = epoch;
        this.content = content;
        this.cacheBreakpoint = cacheBreakpoint;
    }

    public Epoch getEpoch() { return epoch; }

    public String getContent() { return content; }

    public boolean isCacheBreakpoint() { return cacheBreakpoint; }

    /** 是否有实际内容（非空、非空白）。 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
