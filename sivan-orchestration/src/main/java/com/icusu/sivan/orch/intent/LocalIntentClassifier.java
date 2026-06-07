package com.icusu.sivan.orch.intent;

import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.domain.shared.util.CosineSimilarity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地语义意图分类器。
 * <p>
 * 第一级：用 embedding 余弦相似度做本地语义匹配，高置信度直接返回意图。
 * 第二级：低置信度时由 LLM classify，结果反馈回来改进本地分类器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalIntentClassifier {

    private final IEmbeddingService embeddingService;

    /** 本地匹配通过阈值（余弦相似度），高于此值直接使用本地结果。 */
    private static final double CONFIDENCE_THRESHOLD = 0.55;

    /** 每类原型上限。 */
    private static final int MAX_PROTOTYPES_PER_CLASS = 50;

    /** 取 top-K 个相似度平均值做该类得分。 */
    private static final int TOP_K = 3;

    // ========== 原型查询（按意图分类） ==========

    private static final List<String> CHAT_PROTOTYPES = List.of(
            "你好", "您好", "早上好", "下午好", "晚上好", "hello", "hi", "hey",
            "谢谢", "非常感谢", "感谢", "好的", "知道了", "明白了", "ok", "好的谢谢",
            "再见", "拜拜", "goodbye", "bye",
            "今天天气怎么样", "天气", "几点了", "现在几点",
            "什么是依赖注入", "Spring Boot 怎么用", "什么是微服务",
            "帮我介绍一下你自己", "你能做什么", "你是谁",
            "很高兴认识你", "nice to meet you",
            "没关系", "没事", "不用担心",
            "继续", "继续执行", "继续执行任务", "继续吧",
            "开始吧", "开始执行", "执行吧", "可以开始了",
            "继续刚才的", "继续之前的", "继续这个话题"
    );

    private static final List<String> SINGLE_AGENT_PROTOTYPES = List.of(
            "帮我搜索一下", "搜索", "查找", "查询", "search",
            "翻译", "translate", "把这段翻译成英文",
            "写一首诗", "写一篇", "生成文案", "写代码", "coding",
            "计算", "calculate", "compute",
            "总结", "summarize", "摘要",
            "解释一下", "解释这段代码", "这段代码是什么意思",
            "帮我找bug", "这个bug怎么修", "修复问题",
            "生成API", "生成接口", "写一个接口",
            "优化这段代码", "重构", "refactor",
            "把这段代码改成Java", "转换代码", "migrate code",
            "分析这个", "分析这段代码", "analyze",
            "生成单元测试", "写测试", "add tests",
            "读取文件", "读文件", "查看文件",
            "格式化", "format"
    );

    private static final List<String> SQUAD_PROTOTYPES = List.of(
            "设计一个完整的系统架构", "系统设计", "架构设计",
            "生成项目代码", "创建项目", "从零搭建",
            "从数据库设计到API实现", "全栈生成", "全套代码",
            "全面审查代码质量", "代码审查", "code review",
            "多步骤分析", "多步", "流水线",
            "生成开发方案", "技术方案", "实施方案",
            "分析安全性和性能", "全面评估",
            "设计数据库表结构并生成代码",
            "跨多个模块实现功能", "多模块开发",
            "自动化部署方案", "CI/CD 设计",
            "压力测试方案", "性能测试方案",
            "规划重构步骤", "分步骤重构",
            "架构评审", "技术评审"
    );

    /** 意图 → 原型文本列表。 */
    private final Map<Intent, List<String>> prototypes = Map.of(
            Intent.CHAT, CHAT_PROTOTYPES,
            Intent.SINGLE_AGENT, SINGLE_AGENT_PROTOTYPES,
            Intent.SQUAD, SQUAD_PROTOTYPES
    );

    /** 意图 → 原型 embedding 列表（惰性初始化 + 增量更新）。 */
    private final Map<Intent, List<float[]>> prototypeEmbeddings = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /** 从 LLM 反馈学习到的额外原型 embedding（每类固定上限）。 */
    private final Map<Intent, List<float[]>> learnedPrototypes = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (!embeddingService.isAvailable()) {
            log.warn("Embedding 服务不可用，本地意图分类器将降级为 CHAT");
        }
    }

    /**
     * 本地语义分类（仅用户消息）。
     *
     * @param text 用户消息
     * @return 高置信度时返回 Intent，低置信度时返回 null（需交由 LLM classify）
     */
    public Intent classify(String text) {
        return classify(text, null);
    }

    /**
     * 本地语义分类（带上下文感知）。
     * <p>
     * 当用户简短回应（如"需要""好的"）时，结合上一轮助手回复内容做语义匹配，
     * 避免将任务确认误判为普通对话。
     *
     * @param text    用户消息
     * @param context 上一轮助手回复内容（可为 null）
     * @return 高置信度时返回 Intent，低置信度时返回 null
     */
    public Intent classify(String text, String context) {
        if (text == null || text.isBlank()) return Intent.CHAT;
        if (!embeddingService.isAvailable()) {
            return null; // 无 embedding 时交由 LLM
        }

        // 惰性初始化原型 embedding
        if (!initialized) {
            initPrototypeEmbeddings();
            initialized = true;
        }

        // 结合上下文：用户短回应时，上下文提供真实意图信号
        String embedText = text;
        if (context != null && !context.isBlank()) {
            embedText = context + "\n" + text;
        }

        // 对用户消息（+上下文）做 embedding
        float[] textVec = embeddingService.embed(embedText);
        if (textVec == null) {
            log.debug("用户消息 embedding 失败，交由 LLM classify");
            return null;
        }

        // 计算每类的 top-K 平均相似度
        double chatScore = score(textVec, Intent.CHAT);
        double singleScore = score(textVec, Intent.SINGLE_AGENT);
        double squadScore = score(textVec, Intent.SQUAD);

        // 找最高分意图
        Intent bestIntent = Intent.CHAT;
        double bestScore = chatScore;
        if (singleScore > bestScore) { bestScore = singleScore; bestIntent = Intent.SINGLE_AGENT; }
        if (squadScore > bestScore) { bestScore = squadScore; bestIntent = Intent.SQUAD; }

        log.debug("本地意图分类: text='{}' context={} CHAT={} SINGLE_AGENT={} SQUAD={} → best={}({})",
                truncate(text), context != null ? "yes" : "no",
                formatScore(chatScore), formatScore(singleScore),
                formatScore(squadScore), bestIntent, formatScore(bestScore));

        if (bestScore >= CONFIDENCE_THRESHOLD) {
            return bestIntent;
        }
        // 低置信度，交由 LLM classify
        return null;
    }

    /**
     * 记录 LLM 分类结果，用于改进本地分类器。
     * 将高置信度的 LLM 结果作为新原型加入 learnedPrototypes。
     */
    public void recordLlmClassification(String text, Intent intent) {
        if (text == null || text.isBlank() || intent == null || intent == Intent.CHAT) return;
        if (!embeddingService.isAvailable()) return;

        try {
            float[] vec = embeddingService.embed(text);
            if (vec == null) return;

            // 去重：与已有原型相似度 >0.95 则跳过
            List<float[]> existing = getAllPrototypes(intent);
            for (float[] existingVec : existing) {
                if (CosineSimilarity.compute(vec, existingVec) > 0.95) return;
            }

            learnedPrototypes.computeIfAbsent(intent, k -> new CopyOnWriteArrayList<>()).add(vec);

            // 上限淘汰
            List<float[]> list = learnedPrototypes.get(intent);
            while (list.size() > MAX_PROTOTYPES_PER_CLASS) {
                list.removeFirst();
            }

            log.debug("本地分类器学习: intent={}, text='{}' (原型总数={})",
                    intent, truncate(text), list.size());
        } catch (Exception e) {
            log.debug("记录 LLM 分类结果失败: {}", e.getMessage());
        }
    }

    // ========== 内部方法 ==========

    /** 计算用户向量在指定意图上的得分（top-K 平均余弦相似度）。 */
    private double score(float[] textVec, Intent intent) {
        List<float[]> vecs = getAllPrototypes(intent);
        if (vecs.isEmpty()) return 0;

        double[] sims = vecs.stream()
                .mapToDouble(v -> CosineSimilarity.compute(textVec, v))
                .filter(s -> s > 0)
                .sorted()
                .toArray();

        if (sims.length == 0) return 0;

        // 取 top-K 平均
        int k = Math.min(TOP_K, sims.length);
        double sum = 0;
        for (int i = sims.length - k; i < sims.length; i++) {
            sum += sims[i];
        }
        return sum / k;
    }

    /** 获取某意图的全部原型 embedding（原始 + 学习到的）。 */
    private List<float[]> getAllPrototypes(Intent intent) {
        List<float[]> base = prototypeEmbeddings.getOrDefault(intent, List.of());
        List<float[]> learned = learnedPrototypes.getOrDefault(intent, List.of());
        if (learned.isEmpty()) return base;
        List<float[]> all = new ArrayList<>(base.size() + learned.size());
        all.addAll(base);
        all.addAll(learned);
        return all;
    }

    /** 初始化原型 embedding（首次 classify 时惰性加载）。 */
    private synchronized void initPrototypeEmbeddings() {
        if (initialized) return;
        for (Map.Entry<Intent, List<String>> entry : prototypes.entrySet()) {
            Intent intent = entry.getKey();
            List<String> texts = entry.getValue();
            try {
                List<float[]> vecs = embeddingService.embedBatch(texts);
                List<float[]> valid = new ArrayList<>();
                for (int i = 0; i < vecs.size(); i++) {
                    if (vecs.get(i) != null) valid.add(vecs.get(i));
                }
                if (!valid.isEmpty()) {
                    prototypeEmbeddings.put(intent, valid);
                    log.info("本地意图分类原型初始化: intent={}, prototypes={}/{}, 有效={}",
                            intent, valid.size(), texts.size(), valid.size());
                }
            } catch (Exception e) {
                log.warn("意图 {} 原型 embedding 初始化失败: {}", intent, e.getMessage());
            }
        }
        log.info("本地意图分类器初始化完成: CHAT={} SINGLE_AGENT={} SQUAD={}",
                prototypeEmbeddings.getOrDefault(Intent.CHAT, List.of()).size(),
                prototypeEmbeddings.getOrDefault(Intent.SINGLE_AGENT, List.of()).size(),
                prototypeEmbeddings.getOrDefault(Intent.SQUAD, List.of()).size());
    }

    private static String truncate(String s) {
        return s != null && s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static String formatScore(double score) {
        return String.format("%.3f", score);
    }
}
