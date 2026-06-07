package com.icusu.sivan.web.account.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.account.IProfileChangeLogRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.ProfileChangeLog;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import com.icusu.sivan.infra.account.repository.AccountJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Objects;

/**
 * 用户画像自动学习器（批处理模式）。
 * <p>
 * 由 {@link ProfileLearningJob} 定时触发（每日凌晨），
 * 从已存储的对话历史、记忆、工具使用、项目中采集兴趣信号，
 * 融合后写入 {@link UserProfile#expertise}。
 * </p>
 * <p>不复用不建新表、复用已有数据、低频率批量更新。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileLearner {

    private final ModelRouter modelRouter;
    private final IUserProfileRepository userProfileRepository;
    private final IProfileChangeLogRepository profileChangeLogRepository;
    private final IConversationRepository conversationRepository;
    private final IMessageRepository messageRepository;
    private final IMemoryRepository memoryRepository;
    private final IToolUsageRepository toolUsageRepository;
    private final IEmbeddingService embeddingService;
    private final AccountJpaRepository accountJpaRepository;

    /** 每账户最多保留的标签数。 */
    private static final int MAX_TAGS = 10;

    /** 分析对话时提取的最大最近对话数。 */
    private static final int MAX_CONVERSATIONS = 5;

    /** 每轮对话送 LLM 分析的最大消息轮数。 */
    private static final int MAX_EXCHANGES_PER_CONV = 10;

    /** 时间衰减系数（每 7 天 confidence × DECAY_FACTOR）。 */
    private static final double DECAY_FACTOR = 0.85;

    /** 置信度阈值，低于此值的标签被清理。 */
    private static final double CONFIDENCE_THRESHOLD = 0.15;

    /** 手动标签默认置信度（永不衰减）。 */
    private static final double MANUAL_CONFIDENCE = 1.0;

    /** 各源初始置信度。 */
    private static final double CONF_CHAT = 0.5;
    private static final double CONF_MEMORY = 0.6;
    private static final double CONF_TOOL = 0.3;
    private static final double CONF_PROJECT = 0.3;

    /**
     * 对所有账户执行一次画像学习。由定时任务调用。
     */
    public void runBatch() {
        var accounts = accountJpaRepository.findAll();
        for (var accountEntity : accounts) {
            try {
                processAccount(accountEntity.getAccountId());
            } catch (Exception e) {
                log.warn("画像学习异常(跳过): accountId={}, {}", accountEntity.getAccountId(), e.getMessage());
            }
        }
    }

    /** 处理单个账户：采集 → 融合 → 写入。 */
    private void processAccount(UUID accountId) {
        // 1. 采集各源信号
        Map<String, Double> signals = new HashMap<>();

        collectConversationSignals(accountId, signals);
        collectMemorySignals(accountId, signals);
        collectToolSignals(accountId, signals);
        collectProjectSignals(accountId, signals);

        if (signals.isEmpty()) {
            log.debug("画像学习无新信号: accountId={}", accountId);
            return;
        }

        // 2. 加载现有画像
        var profileOpt = userProfileRepository.findByAccountId(accountId);
        if (profileOpt.isEmpty()) return;
        UserProfile profile = profileOpt.get();
        if (!profile.isAutoLearn()) return;

        // 3. 记录现有手动标签（expertise 中已有的视为手动，保留最高权重）
        Set<String> manualTags = profile.getExpertise() != null
                ? new HashSet<>(profile.getExpertise())
                : new HashSet<>();

        // 4. 融合：新信号概率叠加，手动标签始终保留
        Map<String, Double> fused = new HashMap<>();
        for (var e : signals.entrySet()) {
            fused.merge(e.getKey(), e.getValue(), (old, val) -> 1 - (1 - old) * (1 - val));
        }
        // 手动标签设为最高置信度
        for (String tag : manualTags) {
            fused.put(tag, MANUAL_CONFIDENCE);
        }

        // 5. 时间衰减：已有标签降权
        applyDecay(fused, profile);

        // 6. 过滤低置信度 + 取 top-N
        var sorted = fused.entrySet().stream()
                .filter(e -> e.getValue() >= CONFIDENCE_THRESHOLD)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(MAX_TAGS)
                .toList();

        List<String> newExpertise = sorted.stream().map(Map.Entry::getKey).toList();

        // 7. 记录变更日志
        List<String> oldExpertise = profile.getExpertise() != null ? profile.getExpertise() : List.of();
        for (String tag : newExpertise) {
            if (!oldExpertise.contains(tag)) {
                profileChangeLogRepository.save(ProfileChangeLog.of(
                        accountId, "auto_learn", "expertise", null, tag));
            }
        }
        for (String tag : oldExpertise) {
            if (!newExpertise.contains(tag)) {
                profileChangeLogRepository.save(ProfileChangeLog.of(
                        accountId, "auto_learn", "expertise", tag, null));
            }
        }

        // 8. 写入
        profile.setExpertise(new ArrayList<>(newExpertise));
        userProfileRepository.save(profile);

        if (!newExpertise.isEmpty()) {
            log.info("画像学习完成: accountId={}, tags={}", accountId, newExpertise);
        }
    }

    // ====== 信号采集 ======

    /** 从最近对话中提取兴趣标签（语义聚类 → LLM 打标）。 */
    private void collectConversationSignals(UUID accountId, Map<String, Double> signals) {
        // 1. 收集最近对话的交换对
        List<Conversation> conversations = conversationRepository.findAllByAccount(accountId);
        if (conversations.isEmpty()) return;
        conversations.sort(Comparator.comparing(Conversation::getUpdatedAt).reversed());

        List<String> exchangeTexts = new ArrayList<>();
        for (Conversation conv : conversations.subList(0, Math.min(conversations.size(), MAX_CONVERSATIONS))) {
            List<Message> msgs = messageRepository.findLatestByConversationId(conv.getConversationId(), MAX_EXCHANGES_PER_CONV * 2);
            Collections.reverse(msgs);
            for (int i = 0; i + 1 < msgs.size(); i += 2) {
                Message user = msgs.get(i);
                Message asst = msgs.get(i + 1);
                if (!user.isUser()) continue;
                String exchange = truncate((user.getContent() != null ? user.getContent() : ""), 300);
                if (exchange.length() > 10) {
                    exchangeTexts.add(exchange);
                }
            }
        }
        if (exchangeTexts.size() < 3) return; // 太少对话，不足以聚类

        // 2. Embedding 向量化
        if (!embeddingService.isAvailable()) {
            // 回退：直接拼接后送 LLM
            fallbackDirectLlm(accountId, exchangeTexts, signals);
            return;
        }
        List<float[]> vectors = embeddingService.embedBatch(exchangeTexts);
        if (vectors == null || vectors.isEmpty() || vectors.stream().allMatch(Objects::isNull)) {
            fallbackDirectLlm(accountId, exchangeTexts, signals);
            return;
        }

        // 3. 余弦相似度聚类（简单贪心：按序比较，相似度 > 0.85 归为一簇）
        int n = exchangeTexts.size();
        boolean[] assigned = new boolean[n];
        List<List<Integer>> clusters = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (assigned[i] || vectors.get(i) == null) continue;
            List<Integer> cluster = new ArrayList<>();
            cluster.add(i);
            assigned[i] = true;
            for (int j = i + 1; j < n; j++) {
                if (assigned[j] || vectors.get(j) == null) continue;
                double sim = CosineSimilarity.compute(vectors.get(i), vectors.get(j));
                if (sim > 0.85) {
                    cluster.add(j);
                    assigned[j] = true;
                }
            }
            clusters.add(cluster);
        }

        // 4. 按簇大小降序，取每簇的代表文本
        clusters.sort((a, b) -> Integer.compare(b.size(), a.size()));
        StringBuilder summary = new StringBuilder();
        int clusterIdx = 0;
        for (List<Integer> cluster : clusters) {
            if (cluster.size() < 2 && clusters.size() > 3) continue; // 仅保留反复出现的主题
            clusterIdx++;
            summary.append("== 话题 ").append(clusterIdx).append("（出现 ").append(cluster.size()).append(" 次）==\n");
            // 取簇中最长和最短的文本作为代表
            String rep1 = exchangeTexts.get(cluster.getFirst());
            String rep2 = exchangeTexts.get(cluster.get(cluster.size() > 1 ? cluster.size() / 2 : 0));
            summary.append("示例1: ").append(truncate(rep1, 200)).append("\n");
            if (!rep2.equals(rep1)) {
                summary.append("示例2: ").append(truncate(rep2, 200)).append("\n");
            }
            summary.append("\n");
        }

        // 5. LLM 打标
        String prompt = buildAnalyzePrompt(summary.toString());
        try {
            String result = modelRouter.getDefaultModel(accountId)
                    .chat(List.of(Msg.of(Role.USER, prompt)), Model.ModelParams.defaults())
                    .map(r -> r.msg().text())
                    .blockOptional(Duration.ofSeconds(30))
                    .orElse("");
            parseTagLines(result).forEach(tag -> signals.merge(tag, CONF_CHAT, Double::max));
        } catch (Exception e) {
            log.debug("对话兴趣提取失败: {}", e.getMessage());
        }
    }

    /** 回退：无 embedding 服务时直接拼接对话文本送 LLM。 */
    private void fallbackDirectLlm(UUID accountId, List<String> exchangeTexts, Map<String, Double> signals) {
        String combined = String.join("\n---\n", exchangeTexts.subList(0, Math.min(exchangeTexts.size(), 10)));
        try {
            String result = modelRouter.getDefaultModel(accountId)
                    .chat(List.of(Msg.of(Role.USER, buildAnalyzePrompt(combined))), Model.ModelParams.defaults())
                    .map(r -> r.msg().text())
                    .blockOptional(Duration.ofSeconds(30))
                    .orElse("");
            parseTagLines(result).forEach(tag -> signals.merge(tag, CONF_CHAT, Double::max));
        } catch (Exception e) {
            log.debug("对话兴趣提取回退失败: {}", e.getMessage());
        }
    }

    /** 从重要记忆条目中提取关键词。 */
    private void collectMemorySignals(UUID accountId, Map<String, Double> signals) {
        try {
            List<MemoryEntry> important = memoryRepository.findImportant(accountId, null, 20);
            for (var mem : important) {
                String content = mem.getContent();
                if (content == null || content.isBlank()) continue;
                // 提取记忆内容中的关键词（简单：取高频名词片段）
                for (String word : extractKeywords(content, 3)) {
                    signals.merge(word, CONF_MEMORY, Double::max);
                }
            }
        } catch (Exception e) {
            log.debug("记忆信号采集失败: {}", e.getMessage());
        }
    }

    /** 从工具使用记录中提取偏好。 */
    private void collectToolSignals(UUID accountId, Map<String, Double> signals) {
        try {
            var toolCounts = toolUsageRepository.countByToolName(accountId);
            if (toolCounts == null) return;
            int total = toolCounts.stream().mapToInt(row -> ((Number) row[1]).intValue()).sum();
            if (total == 0) return;
            for (Object[] row : toolCounts) {
                String toolName = (String) row[0];
                int count = ((Number) row[1]).intValue();
                if (count < 2) continue; // 仅使用超过 1 次才有信号
                double weight = CONF_TOOL * Math.min(1.0, (double) count / total * 5);
                signals.merge(toolName, weight, Double::max);
            }
        } catch (Exception e) {
            log.debug("工具信号采集失败: {}", e.getMessage());
        }
    }

    /** 从项目信息中提取领域。 */
    private void collectProjectSignals(UUID accountId, Map<String, Double> signals) {
        // 通过直接注入的 JPA repository 查询项目
        try {
            var projects = accountJpaRepository.findById(accountId);
            // 简化：通过 GroupService 获取项目列表，这里直接返回
            // 项目中涉及的表字段暂不额外查询
        } catch (Exception e) {
            log.debug("项目信号采集跳过: {}", e.getMessage());
        }
    }

    // ====== 融合辅助 ======

    /** 对已有标签应用时间衰减。 */
    private void applyDecay(Map<String, Double> fused, UserProfile profile) {
        // 检查 profile 的更新时间，计算周数
        LocalDateTime updated = profile.getUpdatedAt();
        if (updated == null) return;
        long weeks = Duration.between(updated, LocalDateTime.now()).toDays() / 7;
        if (weeks <= 0) return;
        double decay = Math.pow(DECAY_FACTOR, weeks);
        for (var e : fused.entrySet()) {
            if (e.getValue() < MANUAL_CONFIDENCE) { // 手动标签不衰减
                fused.put(e.getKey(), e.getValue() * decay);
            }
        }
    }

    /** 构建 LLM 分析提示词。 */
    private static String buildAnalyzePrompt(String conversationText) {
        return "分析以下用户与 AI 助手的多轮对话记录，从整体判断用户展现出了哪些兴趣领域或技术偏好。\n" +
                "如果没有明显的兴趣信号，只输出 \"NONE\"。\n\n" +
                "要求：\n" +
                "- 每行一个兴趣标签（中文或英文均可），不要编号\n" +
                "- 标签要具体（如\"Python 后端开发\"而非\"编程\"）\n" +
                "- 只提取用户反复提到或明显关注的领域\n" +
                "- 最多输出 5 个标签\n" +
                "- 无信号时输出 \"NONE\"\n\n" +
                "对话记录：\n" + conversationText;
    }

    /** 解析 LLM 返回值中的标签行。 */
    private static List<String> parseTagLines(String text) {
        if (text == null || text.isBlank()) return List.of();
        return text.lines()
                .map(String::strip)
                .filter(l -> !l.isBlank())
                .filter(l -> !"NONE".equalsIgnoreCase(l))
                .map(l -> l.replaceAll("^[-\\d.、\\s]+", ""))
                .filter(l -> l.length() > 1)
                .limit(5)
                .toList();
    }

    /** 截断文本到指定长度。 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 从短文本中提取关键词（简单分段取前 N 个有意义的片段）。 */
    private static List<String> extractKeywords(String text, int max) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[，。、；：\\s,;:]+"))
                .map(String::strip)
                .filter(w -> w.length() > 1 && w.length() < 20)
                .distinct()
                .limit(max)
                .toList();
    }
}
