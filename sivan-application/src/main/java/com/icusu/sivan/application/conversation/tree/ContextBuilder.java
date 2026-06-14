package com.icusu.sivan.application.conversation.tree;

import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.context.ContextForest;
import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.context.ContextTree;
import com.icusu.sivan.domain.context.Epoch;
import com.icusu.sivan.domain.context.TopicNode;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 统一上下文构建器，消费 {@link CompressResult} 和森林中各树，按场景分配预算。
 * <p>
 * CHAT 路径：使用话题树增强压缩上下文（{@link #build}）。
 * Squad 路径：森林架构（SquadTree / MemoryTree / KBTree / ToolChainTree），使用 。
 * Epoch 分段路径：五层 Epoch 感知构建（{@link #buildEpochs}）。
 * <p>
 * CHAT 输出格式：
 * <pre>
 * ## 初始任务/目标
 * {taskGoal}
 *
 * ## 对话历史摘要
 * {inactiveTopic1 summary}
 * {inactiveTopic2 summary}
 *
 * ## 近期对话
 * {hot messages / active topic messages}
 * </pre>
 * <p>
 * SQUAD 输出格式由森林中各树拼接：SquadTree → ConversationTree → MemoryTree → ToolChainTree。
 */
@Component
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final IMessageRepository messageRepository;
    private final ConversationTree conversationTree;
    /** 森林容器，注入各内容类型的树（SquadTree / KBTree / MemoryTree / ToolChainTree）。 */
    @Getter
    private ContextForest forest;

    public ContextBuilder(IMessageRepository messageRepository, ConversationTree conversationTree) {
        this.messageRepository = messageRepository;
        this.conversationTree = conversationTree;
        this.forest = new ContextForest();
    }

    /** 注入森林容器，含 SquadTree / KBTree / MemoryTree / ToolChainTree。 */
    public ContextBuilder withForest(ContextForest forest) {
        this.forest = forest != null ? forest : new ContextForest();
        return this;
    }

    /**
     * 构建增强上下文文本（从 repository 加载消息）。
     *
     * @param conversationId 对话 ID
     * @param compressResult HistoryCompressor 的压缩结果
     * @param maxTokens      上下文预算（token）
     * @return 格式化上下文文本，用于注入 LLM prompt 的 System 消息
     */
    public String build(UUID conversationId, CompressResult compressResult, int maxTokens) {
        try {
            List<Message> allMessages = messageRepository.findByConversationId(conversationId);
            return build(conversationId, compressResult, maxTokens, allMessages);
        } catch (Exception e) {
            log.warn("ContextBuilder 异常(将降级到压缩摘要): conversationId={}", conversationId, e);
            return compressResult.toSummaryText();
        }
    }

    /**
     * 构建增强上下文文本（使用预加载消息列表，避免重复查询）。
     *
     * @param conversationId 对话 ID
     * @param compressResult HistoryCompressor 的压缩结果
     * @param maxTokens      上下文预算（token）
     * @param allMessages    预加载的对话消息列表
     * @return 格式化上下文文本
     */
    public String build(UUID conversationId, CompressResult compressResult, int maxTokens, List<Message> allMessages) {
        try {
            List<Message> chatMessages = allMessages.stream()
                    .filter(m -> m.isUser() || m.isAssistant())
                    .toList();

            List<TopicNode> topics = conversationTree.buildTopics(chatMessages);

            StringBuilder sb = new StringBuilder();

            // Tier 0: 初始任务/目标（硬保留）
            UUID taskGoalId = compressResult.getTaskGoalMsgId();
            if (taskGoalId != null) {
                String taskGoalContent = findMessageContent(taskGoalId, allMessages);
                if (taskGoalContent != null && !taskGoalContent.isBlank()) {
                    sb.append("## 初始任务/目标\n\n").append(taskGoalContent).append("\n\n");
                }
            }

            // Tier 1: 非活跃话题折叠为摘要 + HOT 摘要
            String summary = compressResult.getSummary();
            if (topics.size() > 1) {
                // 有多话题时，按话题组织摘要
                sb.append("## 对话历史摘要\n\n");
                TopicNode activeTopic = findActiveTopic(topics);
                for (TopicNode topic : topics) {
                    if (topic == activeTopic) continue;
                    String topicLabel = topic.getLabel();
                    sb.append("- ").append(topicLabel != null ? "[" + topicLabel + "] " : "");
                    // 取话题内第一条用户消息作为话题摘要
                    String firstContent = findFirstUserContent(topic, allMessages);
                    if (firstContent != null && !firstContent.isBlank()) {
                        sb.append(truncate(firstContent, 100));
                    }
                    sb.append("\n");
                }
                // 追加 WARM/COLD 摘要作为补充
                if (summary != null && !summary.isBlank()) {
                    sb.append("\n").append(summary).append("\n");
                }
                sb.append("\n");
            } else if (summary != null && !summary.isBlank()) {
                // 单话题：直接使用压缩摘要
                sb.append("## 对话历史摘要\n\n").append(summary).append("\n\n");
            }

            // Tier 2: 活跃话题/近期消息全文（来自 hotBatch）
            List<Message> hotBatch = compressResult.getHotBatch();
            if (hotBatch != null && !hotBatch.isEmpty()) {
                sb.append("## 近期对话\n\n");
                for (Message msg : hotBatch) {
                    String role = msg.isUser() ? "用户" : "助手";
                    sb.append(role).append(": ").append(msg.getContent() != null ? msg.getContent() : "").append("\n");
                    if (msg.isAssistant()) sb.append("\n");
                }
            }

            String result = sb.toString().trim();
            if (result.isEmpty()) {
                // 降级：直接使用 compressResult 的文本
                return compressResult.toSummaryText();
            }
            return result;

        } catch (Exception e) {
            log.warn("ContextBuilder 异常(将降级到压缩摘要): conversationId={}", conversationId, e);
            return compressResult.toSummaryText();
        }
    }

    /** 查找活跃话题。 */
    private TopicNode findActiveTopic(List<TopicNode> topics) {
        for (TopicNode t : topics) {
            if (t.isActive()) return t;
        }
        return topics.get(topics.size() - 1);
    }

    /** 从话题中查找第一条用户消息的内容（使用预构建的消息映射）。 */
    private String findFirstUserContent(TopicNode topic, Map<UUID, Message> msgMap) {
        for (UUID mid : topic.getMsgIds()) {
            Message m = msgMap.get(mid);
            if (m != null && m.isUser() && m.getContent() != null) {
                return m.getContent();
            }
        }
        return null;
    }

    /** 从话题中查找第一条用户消息的内容（从消息列表构建映射）。 */
    private String findFirstUserContent(TopicNode topic, List<Message> allMessages) {
        Map<UUID, Message> msgMap = new HashMap<>();
        for (Message m : allMessages) msgMap.put(m.getMessageId(), m);
        return findFirstUserContent(topic, msgMap);
    }

    /** 根据消息 ID 查找消息内容。 */
    private String findMessageContent(UUID msgId, List<Message> allMessages) {
        for (Message m : allMessages) {
            if (m.getMessageId() != null && m.getMessageId().equals(msgId)) {
                return m.getContent();
            }
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ====== Epoch 感知构建 ======

    /**
     * 构建 Epoch 分段上下文（从 repository 加载消息）。
     *
     * @param conversationId 对话 ID
     * @param compressResult HistoryCompressor 的压缩结果
     * @param maxTokens      上下文预算（token）
     * @return Epoch 分段的结果
     */
    public ContextResult buildEpochs(UUID conversationId, CompressResult compressResult, int maxTokens) {
        List<Message> allMessages = messageRepository.findByConversationId(conversationId);
        return buildEpochs(conversationId, compressResult, maxTokens, allMessages);
    }

    /**
     * 构建 Epoch 分段上下文（使用预加载消息列表）。
     *
     * @param conversationId 对话 ID
     * @param compressResult HistoryCompressor 的压缩结果
     * @param maxTokens      上下文预算（token）
     * @param allMessages    预加载的对话消息列表
     * @return Epoch 分段的结果
     */
    public ContextResult buildEpochs(UUID conversationId, CompressResult compressResult, int maxTokens, List<Message> allMessages) {
        try {
            String profileContent = buildEpochFromForest(Epoch.EPOCH_1_PROFILE, maxTokens / 4);
            String completedContent = buildEpochFromForest(Epoch.EPOCH_2_COMPLETED, maxTokens / 4);
            String historyContent = buildHistoryEpoch(conversationId, compressResult, maxTokens / 2, allMessages);
            String activeContent = buildActiveEpoch(compressResult, maxTokens / 2);

            List<ContextSegment> segments = new ArrayList<>();
            segments.add(new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false));
            segments.add(new ContextSegment(Epoch.EPOCH_1_PROFILE, profileContent, false));
            segments.add(new ContextSegment(Epoch.EPOCH_2_COMPLETED, completedContent, false));
            segments.add(new ContextSegment(Epoch.EPOCH_3_HISTORY, historyContent, false));
            segments.add(new ContextSegment(Epoch.EPOCH_4_ACTIVE, activeContent, false));
            return new ContextResult(segments);

        } catch (Exception e) {
            log.warn("ContextBuilder.buildEpochs 异常(将降级到压缩摘要): conversationId={}", conversationId, e);
            String fallback = compressResult != null ? compressResult.toSummaryText() : "";
            List<ContextSegment> fallbackSegments = new ArrayList<>();
            fallbackSegments.add(new ContextSegment(Epoch.EPOCH_0_SYSTEM, "", false));
            fallbackSegments.add(new ContextSegment(Epoch.EPOCH_3_HISTORY, fallback, false));
            return new ContextResult(fallbackSegments);
        }
    }

    /** 从森林中提取指定 Epoch 的内容。 */
    private String buildEpochFromForest(Epoch epoch, int maxTokens) {
        if (forest == null || forest.size() == 0) return "";

        String targetType = switch (epoch) {
            case EPOCH_1_PROFILE -> "memory";
            case EPOCH_2_COMPLETED -> "squad";
            default -> null;
        };
        if (targetType == null) return "";

        ContextTree tree = forest.get(targetType);
        if (tree == null) return "";

        String content = tree.buildContext("CHAT", maxTokens);
        return content != null ? content.trim() : "";
    }

    /** 构建 Epoch 3（对话历史摘要，从 repository 加载消息）。 */
    private String buildHistoryEpoch(UUID conversationId, CompressResult compressResult, int maxTokens) {
        List<Message> allMessages = messageRepository.findByConversationId(conversationId);
        return buildHistoryEpoch(conversationId, compressResult, maxTokens, allMessages);
    }

    /** 构建 Epoch 3（对话历史摘要，使用预加载消息列表）。 */
    private String buildHistoryEpoch(UUID conversationId, CompressResult compressResult, int maxTokens, List<Message> allMessages) {
        List<Message> chatMessages = allMessages.stream().filter(m -> m.isUser() || m.isAssistant()).toList();
        List<TopicNode> topics = conversationTree.buildTopics(chatMessages);

        // 构建消息 ID→对象 映射一次，避免 findFirstUserContent 中重复构造
        Map<UUID, Message> msgMap = new HashMap<>();
        for (Message m : allMessages) msgMap.put(m.getMessageId(), m);

        StringBuilder sb = new StringBuilder();
        int budget = 0;
        UUID taskGoalId = compressResult.getTaskGoalMsgId();
        if (taskGoalId != null) {
            String taskGoalContent = findMessageContent(taskGoalId, allMessages);
            if (taskGoalContent != null && !taskGoalContent.isBlank()) {
                String section = "## 初始任务/目标\n\n" + taskGoalContent + "\n\n";
                budget += estimateTokenLen(section);
                sb.append(section);
            }
        }

        String summary = compressResult.getSummary();
        if (topics.size() > 1) {
            sb.append("## 对话历史摘要\n\n");
            TopicNode activeTopic = findActiveTopic(topics);
            for (TopicNode topic : topics) {
                if (topic == activeTopic) continue;
                String line = "- ";
                if (topic.getLabel() != null) line += "[" + topic.getLabel() + "] ";
                String firstContent = findFirstUserContent(topic, msgMap);
                if (firstContent != null && !firstContent.isBlank()) {
                    line += truncate(firstContent, 100);
                }
                line += "\n";
                int lineTokens = estimateTokenLen(line);
                if (budget + lineTokens > maxTokens) break;
                sb.append(line);
                budget += lineTokens;
            }
            if (summary != null && !summary.isBlank()) {
                sb.append("\n").append(summary).append("\n");
            }
        } else if (summary != null && !summary.isBlank()) {
            sb.append("## 对话历史摘要\n\n").append(summary);
        }
        return sb.toString().trim();
    }

    /** 构建 Epoch 4（活跃上下文）。 */
    private String buildActiveEpoch(CompressResult compressResult, int maxTokens) {
        List<Message> hotBatch = compressResult.getHotBatch();
        if (hotBatch == null || hotBatch.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 近期对话\n\n");
        int budget = 0;
        for (Message msg : hotBatch) {
            String role = msg.isUser() ? "用户" : "助手";
            String line = role + ": " + (msg.getContent() != null ? msg.getContent() : "") + "\n";
            if (msg.isAssistant()) line += "\n";
            int lineTokens = estimateTokenLen(line);
            if (budget + lineTokens > maxTokens) break;
            sb.append(line);
            budget += lineTokens;
        }
        return sb.toString().trim();
    }

    private static int estimateTokenLen(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }
}
