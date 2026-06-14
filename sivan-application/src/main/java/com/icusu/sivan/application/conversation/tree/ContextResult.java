package com.icusu.sivan.application.conversation.tree;

import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.context.Epoch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文构建结果，包含按 Epoch 分段的内容列表。
 * <p>
 * 用于缓存感知的 Prompt 构建：每个 Segment 对应一个 Epoch，
 * 调用方（ForestConversationService）据此构造 LLM 消息列表。
 */
public class ContextResult {

    private final List<ContextSegment> segments;

    public ContextResult(List<ContextSegment> segments) {
        this.segments = segments != null ? segments : List.of();
    }

    public List<ContextSegment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** 获取指定 Epoch 的内容，无内容时返回空字符串。 */
    public String getContent(Epoch epoch) {
        return segments.stream()
                .filter(s -> s.getEpoch() == epoch)
                .map(ContextSegment::getContent)
                .findFirst()
                .orElse("");
    }

    /** 获取缓存命中的 Epoch 索引列表。 */
    public List<Integer> getCachedEpochIndices() {
        List<Integer> cached = new ArrayList<>();
        for (ContextSegment seg : segments) {
            if (seg.isCacheBreakpoint()) {
                cached.add(seg.getEpoch().getIndex());
            }
        }
        return cached;
    }

    /** 合并为单字符串（向后兼容）。 */
    public String toFlatString() {
        StringBuilder sb = new StringBuilder();
        for (ContextSegment seg : segments) {
            if (seg.hasContent()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(seg.getContent());
            }
        }
        return sb.toString().trim();
    }

    /** 是否有任何实际内容。 */
    public boolean isEmpty() {
        return segments.stream().noneMatch(ContextSegment::hasContent);
    }

    /** 分段数。 */
    public int size() {
        return segments.size();
    }

    /** 创建空结果。 */
    public static ContextResult empty() {
        return new ContextResult(List.of());
    }
}
