package com.icusu.sivan.application.conversation;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.MemoryPrompts;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.application.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 对话历史智能压缩器，基于记忆系统实现三层渐进压缩：
 * <ul>
 *   <li>HOT — 最近消息全文保真</li>
 *   <li>WARM — SESSION 记忆加权拼接（纯内存，无 LLM）</li>
 *   <li>COLD — 条目数超阈值时 LLM 浓缩（结构化 JSON）</li>
 * </ul>
 * <p>
 * 输出 {@link CompressResult} 供 ContextBuilder 消费，
 * 包含摘要文本、HOT 消息批次、重要消息 ID 及任务目标消息 ID。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryCompressor {

    private final IMessageRepository messageRepository;
    private final MemoryService memoryService;
    private final ModelRouter modelRouter;

    private static final double CHARS_PER_TOKEN = 2.0;
    /**
     * HOT 层预算占历史总预算的比例
     */
    private static final double HOT_BUDGET_RATIO = 0.55;
    /**
     * 至少保留的近期消息对数（用户+助理为一对）
     */
    private static final int MIN_RECENT_PAIRS = 2;
    /**
     * 触发 COLD 浓缩的最小记忆条目数
     */
    private static final int COLD_THRESHOLD = 50;

    /**
     * 压缩对话历史并返回压缩结果，执行期间通过 progress 回调发射进度事件。
     * 在 boundedElastic 上执行同步工作（DB 查询、LLM 调用）。
     */
    /** 压缩中间状态，承载各阶段结果供后续步骤消费。 */
    private record CompressState(String warmSummary, List<MemoryEntry> sessionMemories, int summaryBudget,
                                 List<Message> hotBatch, List<Message> messages, List<MemoryEntry> userMemories,
                                 UUID taskGoalMsgId) {}

    public Mono<CompressResult> compressStream(UUID conversationId, int maxBudgetTokens, UUID accountId,
                                               Consumer<String> progress) {
        return Mono.defer(() -> {
                    progress.accept("压缩|正在加载历史消息");

                    // 1. 加载并过滤消息
                    List<Message> allMessages = messageRepository.findByConversationId(conversationId);
                    List<Message> messages = allMessages.stream()
                            .filter(m -> m.isUser() || m.isAssistant())
                            .toList();
                    if (messages.isEmpty()) return Mono.just(new CompressResult("", List.of(), List.of(), null, true));

                    // 找到第一条用户消息 ID（任务目标）
                    UUID taskGoalMsgId = findTaskGoalMsgId(messages);

                    int totalTokens = estimateMessagesTokens(messages);

                    // 提前加载用户级长期记忆
                    progress.accept("压缩|正在加载用户长期记忆");
                    List<MemoryEntry> userMemories = memoryService.getUserMemories(accountId);

                    if (totalTokens <= maxBudgetTokens) {
                        String fullText = formatFull(messages, userMemories);
                        // 预算充足时重要消息 ID 包含全部消息
                        List<UUID> allMsgIds = messages.stream()
                                .map(Message::getMessageId)
                                .filter(Objects::nonNull)
                                .toList();
                        return Mono.just(new CompressResult(fullText, messages, allMsgIds, taskGoalMsgId, true));
                    }

                    progress.accept("压缩|正在优化上下文窗口");

                    // 2. 分割 HOT + 旧消息
                    int hotBudget = (int) (maxBudgetTokens * HOT_BUDGET_RATIO);
                    int summaryBudget = maxBudgetTokens - hotBudget;

                    int cutoffIdx = findCutoffIndex(messages, hotBudget);
                    List<Message> hotBatch = messages.subList(cutoffIdx, messages.size());

                    log.info("历史压缩: conversationId={}, {}条消息, {} tokens, 预算{} (HOT={}, 摘要={})",
                            conversationId, messages.size(), totalTokens, maxBudgetTokens, hotBudget, summaryBudget);

                    progress.accept("压缩|正在检索会话记忆");

                    // 3. 查记忆条目（会话级）
                    List<MemoryEntry> sessionMemories = memoryService.getSessionMemories(accountId, conversationId);

                    // 4. WARM 层：加权选择 Top-K 拼接（纯内存，无 LLM）
                    progress.accept("压缩|正在生成摘要");
                    String warmSummary = buildWarmSummary(sessionMemories, summaryBudget);

                    CompressState state = new CompressState(warmSummary, sessionMemories, summaryBudget,
                            hotBatch, messages, userMemories, taskGoalMsgId);

                    // 5. COLD 层：条目数超阈值且信息不足时 LLM 浓缩
                    if (sessionMemories.size() >= COLD_THRESHOLD && needsCold(warmSummary)) {
                        progress.accept("压缩|正在执行深度浓缩（COLD）");
                        log.info("COLD 浓缩触发: {} 条记忆, 预算 {} tokens", sessionMemories.size(), summaryBudget);
                        return performColdCompression(sessionMemories, summaryBudget, accountId)
                                .map(resultSummary -> buildCompressResult(resultSummary, state));
                    }

                    return Mono.just(buildCompressResult(warmSummary, state));
                }
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /** 根据摘要文本和中间状态构建 CompressResult。 */
    private CompressResult buildCompressResult(String resultSummary, CompressState state) {
        String formattedText = formatResult(resultSummary, state.hotBatch,
                findTaskGoal(state.messages), state.userMemories);
        List<UUID> importantIds = new ArrayList<>();
        if (state.taskGoalMsgId != null) importantIds.add(state.taskGoalMsgId);
        for (Message msg : state.hotBatch) {
            if (msg.isUser() && msg.getMessageId() != null
                    && !msg.getMessageId().equals(state.taskGoalMsgId)) {
                importantIds.add(msg.getMessageId());
            }
        }
        return new CompressResult(formattedText, state.hotBatch, importantIds, state.taskGoalMsgId, false);
    }

    // ====== WARM 层 ======

    /**
     * 加权选择记忆条目拼接摘要（纯内存，零 LLM）。
     * 按重要性降序 → 访问频次降序 → 创建时间升序，取 Top-K 条不超预算。
     */
    private String buildWarmSummary(List<MemoryEntry> entries, int maxTokens) {
        if (entries.isEmpty()) return "";

        List<MemoryEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            int cmp = Boolean.compare(
                    b.getImportant() != null && b.getImportant(),
                    a.getImportant() != null && a.getImportant());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(
                    b.getAccessCount() != null ? b.getAccessCount() : 0,
                    a.getAccessCount() != null ? a.getAccessCount() : 0);
            if (cmp != 0) return cmp;
            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            }
            return a.getCreatedAt() != null ? -1 : (b.getCreatedAt() != null ? 1 : 0);
        });

        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (MemoryEntry entry : sorted) {
            boolean isImportant = entry.getImportant() != null && entry.getImportant();
            String text = isImportant ? entry.getContent() : entry.getSummary();
            if (text == null || text.isBlank()) {
                if (isImportant && entry.getSummary() != null && !entry.getSummary().isBlank()) {
                    text = entry.getSummary();
                } else {
                    continue;
                }
            }
            if (isImportant && estimateTokens(text) > maxTokens / 2) {
                text = text.substring(0, Math.min(text.length(), (int) (maxTokens / 2 * CHARS_PER_TOKEN)));
            }
            String line = (sb.length() == 0 ? "" : "\n") + "- " + text;
            int lineTokens = estimateTokens(line);
            if (tokens + lineTokens > maxTokens) break;
            sb.append(line);
            tokens += lineTokens;
        }
        return sb.toString();
    }

    /**
     * 判断是否需要 COLD 浓缩：WARM 信息不足时触发。
     */
    private boolean needsCold(String warmSummary) {
        return warmSummary.isBlank() || estimateTokens(warmSummary) < 50;
    }

    // ====== COLD 层 ======

    /**
     * LLM 浓缩所有记忆条目为结构化摘要。
     */
    private Mono<String> performColdCompression(List<MemoryEntry> entries, int maxTokens, UUID accountId) {
        List<MemoryEntry> nonEmpty = entries.stream()
                .filter(e -> e.getSummary() != null && !e.getSummary().isBlank())
                .toList();
        if (nonEmpty.isEmpty()) return Mono.just("");

        String raw = nonEmpty.stream()
                .map(e -> "- " + e.getSummary())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (estimateTokens(raw) <= maxTokens) return Mono.just(raw);

        List<Msg> msgs = List.of(
                Msg.of(Role.SYSTEM, MemoryPrompts.COLD_COMPRESSION_SYSTEM.content()),
                Msg.of(Role.USER, MemoryPrompts.coldCompressionUser(raw, maxTokens).content())
        );
        return modelRouter.getDefaultModel(accountId).chat(msgs, Model.ModelParams.defaults())
                .timeout(Duration.ofSeconds(15))
                .map(response -> {
                    String result = response.msg().text();
                    return result != null ? result.strip() : raw;
                })
                .onErrorReturn(raw);
    }

    // ====== 边界判定 ======

    /**
     * 从最新消息往前扫描，找到能放入 budget 的截断点。
     */
    private int findCutoffIndex(List<Message> messages, int budget) {
        int total = 0;
        int cutoff = messages.size();

        for (int i = messages.size() - 1; i >= 0; i--) {
            int tokens = estimateMessageTokens(messages.get(i));
            if (total + tokens > budget) {
                cutoff = i + 1;
                break;
            }
            total += tokens;
        }

        // 保证至少保留 MIN_RECENT_PAIRS 对消息
        int minCutoff = Math.max(0, messages.size() - MIN_RECENT_PAIRS * 2);
        return Math.min(cutoff, minCutoff);
    }

    // ====== 格式化 ======

    /**
     * 预算充足时全文格式化。
     */
    private String formatFull(List<Message> messages, List<MemoryEntry> userMemories) {
        StringBuilder sb = new StringBuilder();
        String userContext = formatUserMemories(userMemories);
        if (userContext != null) {
            sb.append("## 用户背景信息\n\n").append(userContext).append("\n\n");
        }
        String taskGoal = findTaskGoal(messages);
        if (taskGoal != null && !taskGoal.isBlank()) {
            sb.append("## 初始任务/目标\n\n").append(taskGoal).append("\n\n");
        }
        sb.append("## 对话历史\n\n");
        for (Message msg : messages) {
            String role = msg.isUser() ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent() != null ? msg.getContent() : "").append("\n");
            if (msg.isAssistant()) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 有记忆摘要时带摘要头。
     */
    private String formatResult(String summary, List<Message> hotBatch, String taskGoal, List<MemoryEntry> userMemories) {
        StringBuilder sb = new StringBuilder();
        String userContext = formatUserMemories(userMemories);
        if (userContext != null) {
            sb.append("## 用户背景信息\n\n").append(userContext).append("\n\n");
        }
        if (taskGoal != null && !taskGoal.isBlank()) {
            sb.append("## 初始任务/目标\n\n").append(taskGoal).append("\n\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("## 对话历史摘要\n\n").append(summary).append("\n\n");
        }
        sb.append("## 近期对话\n\n");
        for (Message msg : hotBatch) {
            String role = msg.isUser() ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent() != null ? msg.getContent() : "").append("\n");
            if (msg.isAssistant()) sb.append("\n");
        }
        return sb.toString();
    }

    // ====== 任务目标提取 ======

    /**
     * 从消息列表中提取第一条用户消息的 ID。
     */
    private UUID findTaskGoalMsgId(List<Message> messages) {
        return messages.stream()
                .filter(Message::isUser)
                .map(Message::getMessageId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * 从消息列表中提取第一条用户消息作为对话的初始任务/目标。
     */
    private String findTaskGoal(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.isUser())
                .map(Message::getContent)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 格式化用户级长期记忆为纯文本摘要。
     */
    private String formatUserMemories(List<MemoryEntry> userMemories) {
        if (userMemories == null || userMemories.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MemoryEntry m : userMemories) {
            String content = m.getContent();
            if (content == null || content.isBlank()) continue;
            if (count > 0) sb.append("\n");
            sb.append("- ").append(content);
            count++;
            if (count >= 5) break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ====== Token 估算 ======

    private int estimateMessagesTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    private int estimateMessageTokens(Message msg) {
        int tokens = estimateTokens(msg.getContent());
        if (msg.getAttachments() != null) {
            tokens += estimateTokens(msg.getAttachments());
        }
        return tokens;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
