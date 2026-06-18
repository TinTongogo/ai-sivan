package com.icusu.sivan.domain.memory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 任务固有特征五维值对象。
 * 用于特征驱动的本能模板匹配和执行路径判定。
 * <p>
 * {@link #toString()} 输出结构化特征摘要，用于路由系统的 feature_hash 计算，
 * 使语义相似的任务映射到相同哈希，提升路由泛化能力。
 * <p>
 * 使用竞争评分（competing scoring）替代首匹配制——每个候选维度独立计分，
 * 高分者胜出，避免首个匹配关键词决定结果的问题。
 *
 * @param complexity     复杂度等级（LEVEL_1~5）
 * @param dependency     依赖关系类型
 * @param inputStructure 输入结构模式
 * @param domain         领域分类
 * @param outputType     输出类型
 */
public record TaskFeatures(
        Complexity complexity,
        Dependency dependency,
        InputStructure inputStructure,
        Domain domain,
        OutputType outputType
) {

    public enum Complexity { LEVEL_1, LEVEL_2, LEVEL_3, LEVEL_4, LEVEL_5 }
    public enum Dependency { INDEPENDENT, SEQUENTIAL, PARALLEL, CONDITIONAL }
    public enum InputStructure { FREE_TEXT, Q_A, CODE, MULTI_MODAL, STRUCTURED_DATA }
    public enum Domain { CODING, WRITING, ANALYSIS, CREATIVE, RESEARCH, GENERAL }
    public enum OutputType { SHORT_TEXT, LONG_TEXT, CODE, JSON, MULTI_MODAL, DECISION }

    /** TF 衰减阈值 — 出现频次超过此值的关键词权重减半（高频词区分度低）。 */
    private static final int TF_DECAY_THRESHOLD = 3;

    // ============================================================
    // 入口
    // ============================================================

    public static TaskFeatures fromContent(String input) {
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return new TaskFeatures(Complexity.LEVEL_1, Dependency.INDEPENDENT,
                    InputStructure.FREE_TEXT, Domain.GENERAL, OutputType.SHORT_TEXT);
        }
        return new TaskFeatures(
                detectComplexity(normalized),
                detectDependency(normalized),
                detectInputStructure(normalized),
                detectDomain(normalized),
                detectOutputType(normalized)
        );
    }

    // ============================================================
    // 归一化
    // ============================================================

    private static String normalize(String input) {
        if (input == null || input.isBlank()) return "";
        String s = input.strip();
        s = stripPolitePrefix(s);
        s = stripTrailingParticles(s);
        s = normalizeQuotesAndPunctuation(s);
        // 去除 URL
        s = URL_PATTERN.matcher(s).replaceAll(" ").strip();
        // 归一化空白
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w./%-]+", Pattern.CASE_INSENSITIVE);

    private static String stripPolitePrefix(String s) {
        String[][] groups = {
            {"帮我", "请帮我", "请你帮我", "你帮我", "帮我一下", "帮我看一下", "我想让你帮我", "我需要你帮我"},
            {"请", "请你"},
            {"你能帮我", "能不能帮我", "可不可以帮我", "可以帮我"},
            {"我想让你", "我需要你", "我希望你"},
            {"我想", "我要", "我来"},
        };
        loop:
        while (true) {
            String before = s;
            for (String[] group : groups) {
                for (String p : group) {
                    if (s.startsWith(p) && s.length() > p.length()) {
                        s = s.substring(p.length()).strip();
                        if (s.isEmpty()) return before; // 只剩前缀时回退
                        continue loop;
                    }
                }
            }
            if (s.length() == before.length()) break;
        }
        return s;
    }

    private static String stripTrailingParticles(String s) {
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if ("吗呢啊吧呀哦？?".indexOf(last) >= 0) {
                s = s.substring(0, s.length() - 1).strip();
            } else break;
        }
        return s;
    }

    private static String normalizeQuotesAndPunctuation(String s) {
        return s.replace("，", ",").replace("；", ";").replace("：", ":")
                .replace("、", ",").replace("（", "(").replace("）", ")")
                .replace("【", "[").replace("】", "]");
    }

    // ============================================================
    // 竞争评分器 — 用于 Domain / OutputType 的竞争分类
    // ============================================================

    /**
     * 竞争评分器。每个类别定义一组关键词及其权重，得分 = 匹配关键词权重之和。
     * 得分最高的类别胜出，无匹配时返回默认值。
     */
    private static final class Scorer<T> {
        private final List<ScoredCategory<T>> categories = new ArrayList<>();

        record ScoredCategory<T>(T label, List<KeywordDef> keywords) {}

        record KeywordDef(String word, double weight) {}

        void add(T label, Object... wordsAndWeights) {
            List<KeywordDef> kws = new ArrayList<>();
            for (int i = 0; i < wordsAndWeights.length; i += 2) {
                String word = (String) wordsAndWeights[i];
                double weight = (Double) wordsAndWeights[i + 1];
                kws.add(new KeywordDef(word, weight));
            }
            categories.add(new ScoredCategory<>(label, kws));
        }

        T score(String input, T defaultLabel) {
            if (input == null || input.isEmpty()) return defaultLabel;
            String lower = input.toLowerCase();
            double bestScore = 0;
            T best = defaultLabel;
            for (var cat : categories) {
                double score = 0;
                for (var kw : cat.keywords()) {
                    if (lower.contains(kw.word())) {
                        // TF 衰减：同一关键词在同一类别中多次匹配不重复加分
                        score += kw.weight();
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = cat.label();
                }
            }
            return best;
        }
    }

    // ============================================================
    // 复杂度
    // ============================================================

    private static Complexity detectComplexity(String input) {
        int len = input.length();
        // 多步骤/多要点信号
        boolean hasMultiStep = containsAny(input, "第一步", "第二步", "首先", "其次", "步骤",
                "1.", "2.", "第1", "第2", "分别", "逐");
        boolean hasMultiPerspective = containsAny(input, "分析.*影响", "综合", "整体", "全面",
                "从.*角度", "多维", "多层次", "全方位");
        int signals = (hasMultiStep ? 1 : 0) + (hasMultiPerspective ? 1 : 0);

        if (len < 20 && signals == 0) return Complexity.LEVEL_1;
        if (len < 60) return Complexity.LEVEL_2;
        if (len < 500) return signals >= 2 ? Complexity.LEVEL_4 : Complexity.LEVEL_3;
        if (len < 2000) return Complexity.LEVEL_4;
        return Complexity.LEVEL_5;
    }

    // ============================================================
    // 依赖关系
    // ============================================================

    private static Dependency detectDependency(String input) {
        if (input == null) return Dependency.INDEPENDENT;
        if (containsAny(input, "并且", "同时", "并行", "都", "各自",
                "分别.*分析", "分别.*处理", "parallel", "concurrent")) {
            if (containsAny(input, "然后", "接着", "再", "之后")) return Dependency.SEQUENTIAL;
            return Dependency.PARALLEL;
        }
        if (containsAny(input, "如果", "判断", "当.*时", "否则", "是否",
                "if.*else", "分支", "假如")) {
            return Dependency.CONDITIONAL;
        }
        if (containsAny(input, "然后", "接着", "再", "之后", "先.*再", "先.*然后",
                "步骤", "分步", "逐步", "按.*顺序")) {
            return Dependency.SEQUENTIAL;
        }
        return Dependency.INDEPENDENT;
    }

    // ============================================================
    // 输入结构
    // ============================================================

    private static InputStructure detectInputStructure(String input) {
        if (input == null) return InputStructure.FREE_TEXT;
        String lower = input.toLowerCase();

        if (input.contains("```")
                || containsAny(lower, "function ", "class ", "def ", "import ",
                        "const ", "let ", "var ", "public ", "private ",
                        "struct ", "fn ", "impl ", "package ",
                        "#include", "require(", "extends ",
                        "@api", "@route"))
            return InputStructure.CODE;

        if (containsAny(lower, "error:", "exception", "stacktrace", "traceback",
                "failed:", "crash", "nullpointer",
                "cannot find", "undeclared", "does not exist"))
            return InputStructure.CODE;

        if (input.contains("\"") && input.contains("{") && input.contains("}") && input.contains(":"))
            return InputStructure.STRUCTURED_DATA;

        if ((input.contains("?") || input.contains("？")) && input.contains("\n"))
            return InputStructure.Q_A;

        if (containsAny(lower, "图片", "图像", "截图", "音频", "视频", "附件"))
            return InputStructure.MULTI_MODAL;

        return InputStructure.FREE_TEXT;
    }

    // ============================================================
    // 领域（竞争评分）
    // ============================================================

    private static final Scorer<Domain> DOMAIN_SCORER = buildDomainScorer();

    private static Scorer<Domain> buildDomainScorer() {
        Scorer<Domain> s = new Scorer<>();
        s.add(Domain.CODING,
                "代码", 2.0, "编程", 2.0, "debug", 2.0, "bug", 2.0,
                "写.*程序", 2.0, "sql", 2.0, "api", 1.5, "git", 1.5,
                "后端", 1.5, "前端", 1.5, "部署", 1.5, "配置", 1.5,
                "编译", 1.5, "docker", 1.5, "maven", 1.5, "npm", 1.5,
                "脚本", 1.5, "class", 1.0, "function", 1.0, "interface", 1.0,
                "java", 1.0, "python", 1.0, "javascript", 1.0, "typescript", 1.0,
                "数据库", 1.5, "报错", 2.0, "重构", 2.0, "接口", 1.5,
                "函数", 1.0, "repository", 1.0, "controller", 1.0, "service", 1.0,
                "repository", 1.0, "controller", 1.0);
        s.add(Domain.WRITING,
                "文章", 2.0, "文案", 2.0, "润色", 2.0, "翻译", 2.0,
                "写作", 1.5, "editor", 1.5, "新闻", 1.5, "稿子", 2.0,
                "内容创作", 2.0, "写.*博客", 2.0, "写.*文章", 2.0, "写.*小说", 2.0,
                "作文", 1.5, "文案策划", 2.0);
        s.add(Domain.ANALYSIS,
                "分析", 2.0, "对比", 2.0, "比较", 1.5, "总结", 1.5,
                "评估", 2.0, "趋势", 2.0, "预测", 2.0, "review", 1.5,
                "audit", 2.0, "影响", 1.5, "优劣势", 2.0, "swot", 2.0,
                "研究报告", 2.0, "数据.*分析", 2.0, "checklist", 1.0,
                "优劣势", 2.0, "可行性", 2.0);
        s.add(Domain.RESEARCH,
                "调研", 2.0, "搜索", 1.5, "查找", 1.0, "查询", 1.0,
                "research", 2.0, "investigate", 2.0, "搜集", 1.5,
                "找.*资料", 2.0, "查找.*信息", 2.0);
        s.add(Domain.CREATIVE,
                "创意", 2.0, "头脑风暴", 2.0, "策划", 1.5, "方案", 1.5,
                "创新", 2.0, "脑洞", 2.0, "设计.*方案", 2.0,
                "蓝海", 2.0, "创意设计", 2.0, "构思", 1.5);
        return s;
    }

    private static Domain detectDomain(String input) {
        return DOMAIN_SCORER.score(input, Domain.GENERAL);
    }

    // ============================================================
    // 输出类型（竞争评分）
    // ============================================================

    private static final Scorer<OutputType> OUTPUT_SCORER = buildOutputScorer();

    private static Scorer<OutputType> buildOutputScorer() {
        Scorer<OutputType> s = new Scorer<>();
        s.add(OutputType.CODE,
                "写代码", 2.0, "写.*脚本", 2.0, "实现.*功能", 2.0,
                "编写.*程序", 2.0, "生成.*代码", 2.0, "写.*sql", 2.0,
                "开发.*功能", 2.0, "debug.*问题", 2.0, "修复.*bug", 2.0,
                "写.*函数", 1.5, "写.*类", 1.5, "写.*api", 1.5,
                "编码", 1.5, "实现", 1.0);
        s.add(OutputType.JSON,
                "json", 2.0, "xml", 2.0, "yaml", 2.0, "生成.*配置", 2.0,
                "接口文档", 2.0, "openapi", 2.0, "生成.*json", 2.0,
                "结构化数据", 2.0, "schema", 2.0, "protobuf", 2.0);
        s.add(OutputType.LONG_TEXT,
                "文章", 2.0, "报告", 2.0, "写.*文档", 2.0, "写.*说明", 2.0,
                "博客", 2.0, "论文", 2.0, "技术方案", 2.0, "方案设计", 2.0,
                "详细说明", 2.0, "完整.*文档", 2.0, "项目建议书", 2.0,
                "可行性分析", 2.0, "分析报告", 2.0, "长文", 2.0);
        s.add(OutputType.MULTI_MODAL,
                "图片", 2.0, "图像", 2.0, "图表", 2.0, "流程图", 2.0,
                "架构图", 2.0, "UML", 2.0, "时序图", 2.0, "思维导图", 2.0,
                "音频", 2.0, "视频", 2.0);
        s.add(OutputType.DECISION,
                "决策", 2.0, "推荐", 2.0, "建议", 1.5, "方案.*选择", 2.0,
                "选型", 2.0, "技术选型", 2.0, "哪个好", 2.0,
                "如何选择", 1.5, "优劣势对比", 2.0, "决定", 2.0,
                "评估.*结果", 2.0, "选.*方案", 2.0);
        return s;
    }

    private static OutputType detectOutputType(String input) {
        return OUTPUT_SCORER.score(input, OutputType.SHORT_TEXT);
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (kw.startsWith(".*")) {
                if (text.contains(kw.substring(2))) return true;
            } else {
                if (text.contains(kw)) return true;
            }
        }
        return false;
    }
}
