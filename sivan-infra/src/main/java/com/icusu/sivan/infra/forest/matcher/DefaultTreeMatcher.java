package com.icusu.sivan.infra.forest.matcher;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.port.TreeMatcher;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 默认树匹配器 — 启发式规则实现。
 * <p>
 * 匹配规则：
 * <ol>
 *   <li>短消息（≤20 字符）→ 单步 {@link TaskNode}</li>
 *   <li>含步骤关键词 → 多步 {@link InnerGoalNode}</li>
 *   <li>含并行关键词 → {@link Mode#PARALLEL}</li>
 *   <li>含条件关键词 → {@link Mode#CONDITIONAL}</li>
 *   <li>含分层关键词 → {@link Mode#HIERARCHICAL}</li>
 *   <li>含多视角关键词 → {@link Mode#CONSENSUS}</li>
 *   <li>默认 → 单步 {@link TaskNode}</li>
 * </ol>
 */
@Component
public class DefaultTreeMatcher implements TreeMatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultTreeMatcher.class);
    private static final int SHORT_MESSAGE_THRESHOLD = 20;

    private static final String[] STEP_KEYWORDS = {
            "步骤", "第一步", "第二步", "第三步", "首先", "然后", "最后",
            "1.", "2.", "3.", "首先", "其次", "同时", "分别"
    };
    private static final String[] PARALLEL_KEYWORDS = { "同时", "分别", "并行", "一起", "同步" };
    private static final String[] CONDITIONAL_KEYWORDS = { "如果", "否则", "条件", "判断" };
    private static final String[] HIERARCHICAL_KEYWORDS = { "分层", "规划", "汇总", "总览" };
    private static final String[] CONSENSUS_KEYWORDS = { "多角度", "多视角", "不同角度", "综合", "全方位" };

    @Override
    public Mono<ExecutableNode> match(String input, UUID accountId) {
        if (input == null || input.isBlank()) {
            log.warn("[TreeMatcher] 输入为空，返回空 TaskNode");
            return Mono.just(new TaskNode(""));
        }

        if (input.length() < SHORT_MESSAGE_THRESHOLD) {
            log.debug("[TreeMatcher] 短消息 -> TaskNode: len={}", input.length());
            return Mono.just(new TaskNode(input));
        }

        // 按优先级检测各模式
        List<TaskNode> steps;

        // 分层模式（HIERARCHICAL）：规划 → 执行 → 汇总
        if (containsAny(input, HIERARCHICAL_KEYWORDS)) {
            steps = extractByLines(input);
            if (steps.size() >= 2) {
                log.debug("[TreeMatcher] -> InnerGoalNode(HIERARCHICAL): stepCount={}", steps.size());
                return Mono.just(new InnerGoalNode(Mode.HIERARCHICAL, steps));
            }
        }

        // 条件模式（CONDITIONAL）：条件判断 → 分支执行
        if (containsAny(input, CONDITIONAL_KEYWORDS)) {
            steps = extractByLines(input);
            if (steps.size() >= 2) {
                log.debug("[TreeMatcher] -> InnerGoalNode(CONDITIONAL): stepCount={}", steps.size());
                return Mono.just(new InnerGoalNode(Mode.CONDITIONAL, steps));
            }
        }

        // 并行模式（PARALLEL）：同时执行多个独立任务
        if (containsAny(input, PARALLEL_KEYWORDS)) {
            steps = extractSteps(input);
            if (steps.size() >= 2) {
                log.debug("[TreeMatcher] -> InnerGoalNode(PARALLEL): stepCount={}", steps.size());
                return Mono.just(new InnerGoalNode(Mode.PARALLEL, steps));
            }
        }

        // 多视角模式（CONSENSUS）：从不同角度分析
        if (containsAny(input, CONSENSUS_KEYWORDS)) {
            steps = extractSteps(input);
            if (steps.size() >= 2) {
                log.debug("[TreeMatcher] -> InnerGoalNode(CONSENSUS): stepCount={}", steps.size());
                return Mono.just(new InnerGoalNode(Mode.CONSENSUS, steps));
            }
        }

        // 顺序模式（SEQUENTIAL）：分步骤按序执行
        if (containsAny(input, STEP_KEYWORDS)) {
            steps = extractSteps(input);
            if (steps.size() >= 2) {
                log.debug("[TreeMatcher] -> InnerGoalNode(SEQUENTIAL): stepCount={}", steps.size());
                return Mono.just(new InnerGoalNode(Mode.SEQUENTIAL, steps));
            }
            log.debug("[TreeMatcher] 含关键词但无法拆分 -> TaskNode");
            return Mono.just(new TaskNode(input));
        }

        log.debug("[TreeMatcher] 默认 -> TaskNode");
        return Mono.just(new TaskNode(input));
    }

    // ===== 关键词检测 =====

    private static boolean containsAny(String input, String[] keywords) {
        for (String kw : keywords) {
            if (input.contains(kw)) return true;
        }
        return false;
    }

    // ===== 步骤提取 =====

    static List<TaskNode> extractSteps(String input) {
        List<TaskNode> steps = extractByLines(input);
        if (steps.size() >= 2) return steps;

        steps = extractByKeywords(input);
        if (steps.size() >= 2) return steps;

        return List.of();
    }

    private static List<TaskNode> extractByLines(String input) {
        List<TaskNode> steps = new ArrayList<>();
        String[] lines = input.split("\n");
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.length() <= 2) continue;
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.startsWith("*")) {
                String content = trimmed.replaceAll("^[-•*\\s]+", "").strip();
                if (!content.isEmpty()) steps.add(new TaskNode(content));
            } else if (trimmed.matches("^\\d+[.、)）].*")) {
                String content = trimmed.replaceAll("^\\d+[.、)）]\\s*", "").strip();
                if (!content.isEmpty()) steps.add(new TaskNode(content));
            }
        }
        return steps;
    }

    private static List<TaskNode> extractByKeywords(String input) {
        String[] parts = input.split(
                "(?<=[。！？;；])\\s*(?=(首先|第一|先|第一步|第二|然后|其次|接着|再|第三|最后|第四|第五|第六))" +
                "|(?<=^)\\s*(?=首先|第一|先|第一步)",
                0
        );
        if (parts.length < 2) return List.of();

        List<TaskNode> steps = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part
                    .replaceAll("^(首先|第一|先|第一步|第二|然后|其次|接着|再|第三|最后|第四|第五|第六)[，,、：:\\s]*", "")
                    .replaceAll("[。！？!?;；]+$", "")
                    .strip();
            if (cleaned.length() > 2) {
                steps.add(new TaskNode(cleaned));
            }
        }
        return steps.size() >= 2 ? steps : List.of();
    }
}
