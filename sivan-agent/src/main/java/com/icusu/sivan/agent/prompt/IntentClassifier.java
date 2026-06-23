package com.icusu.sivan.agent.prompt;

import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.infra.forest.entity.IntentFeedbackLogEntity;
import com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity;
import com.icusu.sivan.infra.forest.repository.IntentFeedbackLogJpaRepository;
import com.icusu.sivan.infra.forest.repository.IntentPrototypeJpaRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 意图分类器 — 用 embedding 语义相似度判断用户输入是"闲聊"还是"任务"。
 * <p>
 * 闲聊和任务的原型文本存储在 DB（{@code intent_prototypes} 表），用户可通过 API 自定义。
 * 每次分类记录到 {@code intent_feedback_log}，用户纠正后触发原型自动调整，形成持续学习闭环。
 * embedding 不可用时降级到关键词匹配。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 硬编码默认值 — DB 无配置时的兜底 */
    private static final String DEFAULT_CHAT = "简单的问候聊天、日常对话、打招呼、表达感受、"
            + "闲聊日常话题，不需要任何工具或操作，不需要处理文件或执行代码";
    private static final String DEFAULT_TASK = "需要执行具体任务、操作文件、分析代码、"
            + "运行计算、生成内容、处理数据、搜索查找、系统操作";

    /** 关键词兜底 — embedding 不可用时降级 */
    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "分析", "处理", "生成", "创建", "执行", "查找", "计算", "翻译", "总结",
            "提取", "转换", "合并", "拆分", "比较", "统计", "预测", "优化",
            "评估", "评价", "检查", "审查", "审核", "了解", "说明", "描述",
            "推荐", "建议", "规划", "方案", "策略", "思路",
            "开发", "研发", "构建", "搭建", "配置", "调试", "修复",
            "写", "改", "删", "查", "修", "重构", "设计", "实现", "测试", "部署",
            "analyze", "process", "generate", "create", "execute", "find", "calculate",
            "translate", "summarize", "extract", "convert", "merge", "split", "compare",
            "write", "edit", "delete", "query", "fix", "refactor", "design", "implement",
            "test", "deploy", "evaluate", "review", "check", "plan", "build", "develop");

    /** 触发原型调整的最小纠正数 */
    private static final int ADJUST_THRESHOLD = 10;

    private final IntentPrototypeJpaRepository prototypeRepo;
    private final IntentFeedbackLogJpaRepository feedbackRepo;
    private final IEmbeddingService embeddingService;

    /** 当前使用的原型文本 */
    @Getter
    private volatile String chatPrototype = DEFAULT_CHAT;
    @Getter
    private volatile String taskPrototype = DEFAULT_TASK;

    /** 原型 embedding 缓存 */
    private volatile float[] chatEmbedding;
    private volatile float[] taskEmbedding;
    private volatile boolean embeddingsReady = false;
    private final Object lock = new Object();

    public IntentClassifier(IntentPrototypeJpaRepository prototypeRepo,
                            IntentFeedbackLogJpaRepository feedbackRepo,
                            IEmbeddingService embeddingService) {
        this.prototypeRepo = prototypeRepo;
        this.feedbackRepo = feedbackRepo;
        this.embeddingService = embeddingService;
    }

    /** 启动时从 DB 加载原型。 */
    @PostConstruct
    void init() {
        reload();
    }

    /**
     * 从 DB 重新加载原型文本并清除 embedding 缓存。
     */
    public void reload() {
        try {
            prototypeRepo.findByPrototypeKey("chat")
                    .map(IntentPrototypeEntity::getPrototypeText)
                    .ifPresent(text -> chatPrototype = text);
            prototypeRepo.findByPrototypeKey("task")
                    .map(IntentPrototypeEntity::getPrototypeText)
                    .ifPresent(text -> taskPrototype = text);
            synchronized (lock) {
                chatEmbedding = null;
                taskEmbedding = null;
                embeddingsReady = false;
            }
            log.info("[IntentClassifier] 原型已加载: chat={}chars task={}chars",
                    chatPrototype.length(), taskPrototype.length());
        } catch (Exception e) {
            log.warn("[IntentClassifier] 从 DB 加载原型失败，使用默认值: {}", e.getMessage());
        }
    }

    // ========== 分类决策 ==========

    /**
     * 判断输入是否为需要执行的任务（vs 简单闲聊），不记录日志。
     */
    public boolean isTask(String input) {
        return isTask(input, null, null);
    }

    /**
     * 判断输入是否为需要执行的任务，记录分类日志。
     *
     * @param input    用户输入
     * @param accountId 账户 ID（为 null 时不记录日志）
     * @return true = 需要执行的任务 / false = 简单闲聊
     */
    public boolean isTask(String input, UUID accountId) {
        return isTask(input, accountId, null);
    }

    /**
     * 判断输入是否为需要执行的任务，记录分类日志。
     *
     * @param input    用户输入
     * @param accountId 账户 ID
     * @param convId    对话 ID（可为 null）
     * @return true = 需要执行的任务 / false = 简单闲聊
     */
    public boolean isTask(String input, UUID accountId, UUID convId) {
        if (input == null || input.isBlank()) return false;

        ensureEmbeddings();

        try {
            float[] inputEmb = embeddingService.embed(input);
            if (inputEmb == null) {
                boolean result = fallback(input);
                logFeedback(input, accountId, convId, null, result);
                return result;
            }

            double simToChat = cosineSimilarity(inputEmb, chatEmbedding);
            double simToTask = cosineSimilarity(inputEmb, taskEmbedding);
            boolean result = simToTask > simToChat;

            logFeedback(input, accountId, convId, simToTask - simToChat, result);
            return result;
        } catch (Exception e) {
            boolean result = fallback(input);
            logFeedback(input, accountId, convId, null, result);
            return result;
        }
    }

    // ========== 节点级反馈（执行质量） ==========

    /**
     * 记录执行树节点的反馈（与意图分类无关，用于评估执行路径质量）。
     *
     * @param accountId 账户 ID
     * @param nodeName  节点名称（任务描述）
     * @param rating    like / dislike
     */
    public void logNodeFeedback(UUID accountId, String nodeName, String rating) {
        if (accountId == null || nodeName == null || rating == null) return;
        try {
            IntentFeedbackLogEntity entity = IntentFeedbackLogEntity.builder()
                    .accountId(accountId)
                    .messageText(nodeName.length() > 500 ? nodeName.substring(0, 500) : nodeName)
                    .predictedIntent("node_feedback")
                    .userCorrection(rating)
                    .build();
            feedbackRepo.save(entity);
            log.debug("[IntentClassifier] 节点反馈已记录: node={} rating={}", nodeName, rating);
        } catch (Exception e) {
            log.warn("[IntentClassifier] 记录节点反馈失败: {}", e.getMessage());
        }
    }

    // ========== 消息级纠正（意图分类） ==========

    /**
     * 记录用户对某条消息的分类纠正。
     *
     * @param messageId  消息 ID
     * @param wasCorrect true=分类正确 / false=分类错误
     */
    public void recordCorrection(UUID messageId, boolean wasCorrect) {
        if (messageId == null) return;
        try {
            feedbackRepo.findByMessageId(messageId).ifPresent(entity -> {
                entity.setUserCorrection(wasCorrect ? "correct" : "incorrect");
                entity.setCorrectedIntent(
                        wasCorrect ? entity.getPredictedIntent()
                                   : ("chat".equals(entity.getPredictedIntent()) ? "task" : "chat"));
                feedbackRepo.save(entity);

                if (!wasCorrect) {
                    // 积累纠正数达到阈值时触发原型调整
                    long totalIncorrect = feedbackRepo.countByAccountIdAndUserCorrection(
                            entity.getAccountId(), "incorrect");
                    if (totalIncorrect % ADJUST_THRESHOLD == 0) {
                        adjustPrototypes(entity.getAccountId());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("[IntentClassifier] 记录纠正失败: messageId={} error={}", messageId, e.getMessage());
        }
    }

    /**
     * 根据用户的历史纠正调整原型文本。
     * 收集被纠正为"task"的消息，计算平均 embedding，更新 task 原型。
     */
    private void adjustPrototypes(UUID accountId) {
        try {
            // 收集该用户最近被纠正为 task 的消息
            var logs = feedbackRepo.findByAccountIdOrderByCreatedAtDesc(
                    accountId, PageRequest.of(0, ADJUST_THRESHOLD));
            if (logs.isEmpty()) return;

            // 筛选被纠正为 task 的消息
            var taskCorrections = logs.stream()
                    .filter(l -> "incorrect".equals(l.getUserCorrection())
                            && "task".equals(l.getCorrectedIntent()))
                    .toList();
            if (taskCorrections.size() < 3) return; // 太少不调

            // 计算这些消息的平均 embedding
            float[] avgEmb = new float[0];
            int count = 0;
            for (var log_ : taskCorrections) {
                float[] emb = embeddingService.embed(log_.getMessageText());
                if (emb != null) {
                    if (avgEmb.length == 0) avgEmb = new float[emb.length];
                    for (int i = 0; i < emb.length; i++) avgEmb[i] += emb[i];
                    count++;
                }
            }
            if (count == 0) return;
            for (int i = 0; i < avgEmb.length; i++) avgEmb[i] /= count;

            // 与原原型 embedding 加权平均（权重：纠正数/阈值）
            float[] taskEmb = taskEmbedding;
            if (taskEmb == null) taskEmb = embeddingService.embed(taskPrototype);
            if (taskEmb == null) return;

            double weight = Math.min((double) count / ADJUST_THRESHOLD, 0.5);
            for (int i = 0; i < avgEmb.length; i++) {
                avgEmb[i] = (float) (avgEmb[i] * weight + taskEmb[i] * (1 - weight));
            }

            // 更新原型文本 — 取与平均向量最接近的消息文本作为新原型
            String newTaskText = taskPrototype;
            double bestSim = 0;
            for (var log_ : taskCorrections) {
                float[] emb = embeddingService.embed(log_.getMessageText());
                if (emb != null) {
                    double sim = cosineSimilarity(avgEmb, emb);
                    if (sim > bestSim) {
                        bestSim = sim;
                        newTaskText = log_.getMessageText();
                    }
                }
            }

            // 持久化并刷新缓存
            if (!newTaskText.equals(taskPrototype)) {
                var now = OffsetDateTime.now();
                prototypeRepo.save(IntentPrototypeEntity.builder()
                        .prototypeKey("task").prototypeText(newTaskText).updatedAt(now).build());
                taskPrototype = newTaskText;
                synchronized (lock) { embeddingsReady = false; }
                log.info("[IntentClassifier] 原型已调整: task={}chars (基于 {} 条纠正)",
                        newTaskText.length(), count);
            }
        } catch (Exception e) {
            log.warn("[IntentClassifier] 原型调整失败: {}", e.getMessage());
        }
    }

    // ========== 日志查询（供管理 API） ==========

    public long getTotalFeedbackCount(UUID accountId) {
        return feedbackRepo.countByAccountIdAndUserCorrectionIsNotNull(accountId);
    }

    public long getIncorrectCount(UUID accountId) {
        return feedbackRepo.countByAccountIdAndUserCorrection(accountId, "incorrect");
    }

    public List<IntentFeedbackLogEntity> getRecentLogs(UUID accountId, int limit) {
        return feedbackRepo.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, limit));
    }

    // ========== 内部方法 ==========

    /** 记录分类日志。 */
    private void logFeedback(String input, UUID accountId, UUID convId, Double confidence, boolean result) {
        if (accountId == null) return;
        try {
            IntentFeedbackLogEntity entity = IntentFeedbackLogEntity.builder()
                    .accountId(accountId)
                    .conversationId(convId)
                    .messageText(input.length() > 500 ? input.substring(0, 500) : input)
                    .predictedIntent(result ? "task" : "chat")
                    .confidence(confidence)
                    .build();
            feedbackRepo.save(entity);
        } catch (Exception e) {
            log.debug("[IntentClassifier] 记录分类日志失败: {}", e.getMessage());
        }
    }

    /** 确保原型 embedding 已计算。 */
    private void ensureEmbeddings() {
        if (!embeddingsReady) {
            synchronized (lock) {
                if (!embeddingsReady) {
                    try {
                        chatEmbedding = embeddingService.embed(chatPrototype);
                        taskEmbedding = embeddingService.embed(taskPrototype);
                        embeddingsReady = true;
                        log.info("[IntentClassifier] 原型 embedding 已计算");
                    } catch (Exception e) {
                        log.warn("[IntentClassifier] 计算原型 embedding 失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /** embedding 不可用时降级到关键词匹配。 */
    private static boolean fallback(String input) {
        if (input == null || input.isBlank()) return false;
        String lower = input.toLowerCase();
        for (String kw : FALLBACK_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** 余弦相似度。 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return (normA > 0 && normB > 0) ? dot / (Math.sqrt(normA) * Math.sqrt(normB)) : 0;
    }
}
