package com.icusu.sivan.memory.pattern;

import com.icusu.sivan.domain.task.TaskFeatures;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 特征提取器。L1 启发式规则 + L2 LLM 提取。
 *
 * <p>L1 基于规则快速判定，零 LLM 调用；
 * L2 在 L1 无法覆盖足够维度时由调用方提供 LLM 分类函数。
 */
@Component
public class FeatureExtractor {

    /** L1 覆盖 ≥ 3 维度时无需 L2。 */
    private static final int L1_COVERAGE_THRESHOLD = 3;

    /**
     * 仅执行 L1 启发式提取。
     *
     * @param taskDescription 任务描述文本
     * @return 提取结果，覆盖不足 3 维时部分维度为 null
     */
    public TaskFeatures extractHeuristic(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return new TaskFeatures(
                    TaskFeatures.Complexity.LEVEL_1,
                    null, null, null, null
            );
        }
        return new L1Extractor(taskDescription).extract();
    }

    /**
     * L1 + L2 完整提取。L1 覆盖不足时调用 llmClassifier。
     *
     * @param taskDescription 任务描述文本
     * @param llmClassifier   L2 LLM 分类函数
     * @return 完整特征
     */
    public TaskFeatures extract(String taskDescription,
                                 Function<String, TaskFeatures> llmClassifier) {
        TaskFeatures heuristic = extractHeuristic(taskDescription);
        if (coverage(heuristic) >= L1_COVERAGE_THRESHOLD) {
            return heuristic;
        }
        TaskFeatures llmResult = llmClassifier.apply(taskDescription);
        return merge(heuristic, llmResult);
    }

    /**
     * 统计 L1 覆盖的维度数。
     */
    static int coverage(TaskFeatures features) {
        int count = 0;
        if (features.complexity() != null) count++;
        if (features.dependency() != null) count++;
        if (features.inputStructure() != null) count++;
        if (features.domain() != null) count++;
        if (features.outputType() != null) count++;
        return count;
    }

    /**
     * 合并 L1 和 L2 结果：L1 优先，L1 缺失的维度用 L2 补全。
     */
    static TaskFeatures merge(TaskFeatures l1, TaskFeatures l2) {
        return new TaskFeatures(
                l1.complexity() != null ? l1.complexity() : l2.complexity(),
                l1.dependency() != null ? l1.dependency() : l2.dependency(),
                l1.inputStructure() != null ? l1.inputStructure() : l2.inputStructure(),
                l1.domain() != null ? l1.domain() : l2.domain(),
                l1.outputType() != null ? l1.outputType() : l2.outputType()
        );
    }

    // ===== 预编译正则（避免在热路径上反复 Pattern.compile + 防止 ReDoS） =====

    /** 步骤指示词（纯文本匹配，无正则元字符）。 */
    private static final String[] STEP_WORDS = {"步骤", "首先", "然后", "最后", "第一步", "第二步", "依次"};
    /** 步骤指示正则（含分隔符，独立编译以避免 ReDoS）。 */
    private static final Pattern STEP1 = Pattern.compile("1\\.");
    private static final Pattern STEP2 = Pattern.compile("2\\.");
    private static final Pattern STEP3 = Pattern.compile("3\\.");
    /** "分...步" 模式，使用原子组防止回溯爆炸。 */
    private static final Pattern STEP_FEN = Pattern.compile("分.*+步");
    /** 各类规则正则，预编译防止热路径重复编译。 */
    private static final Pattern PATTERN_CLASS = Pattern.compile("class\\s+\\w+");
    private static final Pattern PATTERN_BACKTICK = Pattern.compile("`[a-z]+");
    private static final Pattern PATTERN_QUESTION = Pattern.compile("\\?");
    private static final Pattern PATTERN_MA = Pattern.compile("吗\\b");
    private static final Pattern PATTERN_LINK = Pattern.compile("\\[.*+\\]\\(.*+\\)");
    private static final Pattern PATTERN_WWW = Pattern.compile("www\\.");
    private static final Pattern PATTERN_BRACE = Pattern.compile("\\{.*+\\}");
    private static final Pattern PATTERN_BRACKET = Pattern.compile("\\[.*+\\]");
    private static final Pattern PATTERN_IF_THEN = Pattern.compile("if.*+then");
    private static final Pattern PATTERN_DANG = Pattern.compile("当.*+时");
    private static final Pattern PATTERN_YIBIAN = Pattern.compile("一边.*+一边");

    // ===== L1 提取器 =====

    static class L1Extractor {
        final String text;
        final String lower;

        L1Extractor(String text) {
            this.text = text;
            this.lower = text.toLowerCase();
        }

        TaskFeatures extract() {
            return new TaskFeatures(
                    extractComplexity(),
                    extractDependency(),
                    extractInputStructure(),
                    extractDomain(),
                    extractOutputType()
            );
        }

        TaskFeatures.Complexity extractComplexity() {
            int steps = countStepIndicators();
            if (steps >= 3) return TaskFeatures.Complexity.LEVEL_4;
            if (steps >= 1) return TaskFeatures.Complexity.LEVEL_3;

            int len = text.length();
            if (len <= 10) return TaskFeatures.Complexity.LEVEL_1;
            if (len <= 50) return TaskFeatures.Complexity.LEVEL_2;

            // 长文本 → LEVEL_3
            return TaskFeatures.Complexity.LEVEL_3;
        }

        TaskFeatures.Dependency extractDependency() {
            // 条件依赖 → CONDITIONAL
            if (containsAny("如果", "若", "假如", "depending")
                    || PATTERN_IF_THEN.matcher(lower).find()
                    || PATTERN_DANG.matcher(lower).find()) {
                return TaskFeatures.Dependency.CONDITIONAL;
            }
            // 并行 → PARALLEL
            if (containsAny("同时", "分别", "parallel", "concurrent")
                    || PATTERN_YIBIAN.matcher(lower).find()) {
                return TaskFeatures.Dependency.PARALLEL;
            }
            // 序列 → SEQUENTIAL
            if (countStepIndicators() >= 2) {
                return TaskFeatures.Dependency.SEQUENTIAL;
            }
            return null;
        }

        TaskFeatures.InputStructure extractInputStructure() {
            if (containsAny("```", "代码", "报错", "error", "exception")
                    || PATTERN_BACKTICK.matcher(lower).find()) {
                return TaskFeatures.InputStructure.CODE;
            }
            if (PATTERN_QUESTION.matcher(lower).find()
                    || PATTERN_MA.matcher(lower).find()
                    || containsAny("什么", "如何", "怎样", "why", "how", "what")) {
                return TaskFeatures.InputStructure.Q_A;
            }
            if (containsAny("http")
                    || PATTERN_LINK.matcher(lower).find()
                    || PATTERN_WWW.matcher(lower).find()) {
                return TaskFeatures.InputStructure.MULTI_MODAL;
            }
            if (containsAny("表格", "数据", "csv", "json")
                    || PATTERN_BRACE.matcher(lower).find()
                    || PATTERN_BRACKET.matcher(lower).find()) {
                return TaskFeatures.InputStructure.STRUCTURED_DATA;
            }
            return null;
        }

        TaskFeatures.Domain extractDomain() {
            if (containsAny("代码", "debug", "bug", "修复", "开发", "program", "code", "function", "api", "实现")
                    || PATTERN_CLASS.matcher(lower).find()) {
                return TaskFeatures.Domain.CODING;
            }
            if (containsAny("写", "创作", "文案", "文章", "博客", "作文", "essay", "write", "draft")) {
                return TaskFeatures.Domain.WRITING;
            }
            if (containsAny("分析", "比较", "总结", "对比", "评估", "analyze", "compare", "summarize")) {
                return TaskFeatures.Domain.ANALYSIS;
            }
            if (containsAny("创意", "设计", "头脑风暴", "brainstorm", "design", "create")) {
                return TaskFeatures.Domain.CREATIVE;
            }
            if (containsAny("调研", "研究", "搜索", "查", "research", "find", "search")) {
                return TaskFeatures.Domain.RESEARCH;
            }
            return null;
        }

        TaskFeatures.OutputType extractOutputType() {
            if (containsAny("```", "代码", "function", "接口")
                    || PATTERN_CLASS.matcher(lower).find()) {
                return TaskFeatures.OutputType.CODE;
            }
            if (containsAny("json", "xml", "yaml", "配置")) {
                return TaskFeatures.OutputType.JSON;
            }
            if (containsAny("决策", "决定", "选择", "推荐", "判断", "decide", "recommend")) {
                return TaskFeatures.OutputType.DECISION;
            }
            if (containsAny("图片", "图", "diagram", "mermaid")) {
                return TaskFeatures.OutputType.MULTI_MODAL;
            }
            int len = text.length();
            if (len >= 100) return TaskFeatures.OutputType.LONG_TEXT;
            if (len <= 20) return TaskFeatures.OutputType.SHORT_TEXT;
            return null;
        }

        /** 纯字符串包含检查（替代正则，避免 ReDoS）。 */
        boolean containsAny(String... keywords) {
            for (String kw : keywords) {
                if (lower.contains(kw)) return true;
            }
            return false;
        }

        int countStepIndicators() {
            int count = 0;
            for (String word : STEP_WORDS) {
                if (lower.contains(word)) count++;
            }
            if (STEP1.matcher(lower).find()) count++;
            if (STEP2.matcher(lower).find()) count++;
            if (STEP3.matcher(lower).find()) count++;
            if (STEP_FEN.matcher(lower).find()) count++;
            return count;
        }
    }
}
