package com.icusu.sivan.domain.context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 话题节点，表示对话树中的一个话题岛。
 * 由 TF-IDF 相似度判定边界，同一话题内的消息共享一个节点。
 */
public class TopicNode {

    private UUID topicId;
    /** TF-IDF top 词作为话题标签 */
    private String label;
    /** 属于该话题的消息 ID 列表（按时间排序） */
    private List<UUID> msgIds;
    /** 话题摘要（来自 CompressResult） */
    private String summary;
    /** 当前是否为活跃话题 */
    private boolean active;

    public TopicNode() {
        this.topicId = UUID.randomUUID();
        this.msgIds = new ArrayList<>();
        this.active = false;
    }

    public TopicNode(String label, UUID firstMsgId) {
        this();
        this.label = label;
        this.msgIds.add(firstMsgId);
        this.active = true;
    }

    public UUID getTopicId() { return topicId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public List<UUID> getMsgIds() { return msgIds; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public void addMessage(UUID msgId) {
        this.msgIds.add(msgId);
    }

    public int size() { return msgIds.size(); }

    @Override
    public String toString() {
        return "TopicNode{" +
                "label='" + label + '\'' +
                ", msgCount=" + msgIds.size() +
                ", active=" + active +
                '}';
    }
}
