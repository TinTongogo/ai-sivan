package com.icusu.sivan.domain.conversation;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 历史压缩结果，由 HistoryCompressor 产出，供 ContextBuilder 消费。
 * <p>
 * 包含三层压缩的输出：
 * <ul>
 *   <li>HOT — 近期消息全文（hotBatch）</li>
 *   <li>WARM/COLD — 摘要文本（summary）</li>
 *   <li>重要消息保护 — importantMsgIds + taskGoalMsgId</li>
 * </ul>
 */
public class CompressResult {

    /** WARM/COLD 拼接的摘要文本，或 formatFull 全文 */
    private final String summary;

    /** HOT 层保留的近期消息全文（最新消息在列表末尾） */
    private final List<Message> hotBatch;

    /** 需要保护的重要消息 ID（WARM 高优先级 + taskGoal） */
    private final List<UUID> importantMsgIds;

    /** 第一条用户消息 ID（对话任务主线，硬约束保留） */
    private final UUID taskGoalMsgId;

    /** true = 预算充足，全文返回未压缩 */
    private final boolean fullText;

    public CompressResult(String summary, List<Message> hotBatch,
                          List<UUID> importantMsgIds, UUID taskGoalMsgId,
                          boolean fullText) {
        this.summary = summary;
        this.hotBatch = hotBatch != null ? hotBatch : Collections.emptyList();
        this.importantMsgIds = importantMsgIds != null ? importantMsgIds : Collections.emptyList();
        this.taskGoalMsgId = taskGoalMsgId;
        this.fullText = fullText;
    }

    public String getSummary() { return summary; }
    public List<Message> getHotBatch() { return hotBatch; }
    public List<UUID> getImportantMsgIds() { return importantMsgIds; }
    public UUID getTaskGoalMsgId() { return taskGoalMsgId; }
    public boolean isFullText() { return fullText; }

    /** 获取格式化后的纯文本摘要（兼容旧调用方，相当于原来的 compressStream 返回值）。 */
    public String toSummaryText() {
        return summary != null ? summary : "";
    }
}
