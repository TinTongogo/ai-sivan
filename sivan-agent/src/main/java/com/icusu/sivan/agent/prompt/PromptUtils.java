package com.icusu.sivan.agent.prompt;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提示词工具集：公共常量、安全转义、参数截断、Token 估算、调用统计。
 */
public final class PromptUtils {

    private PromptUtils() {}

    /** 要求 LLM 输出 JSON 对象的尾缀。 */
    public static final String JSON_ONLY =
            "请严格按以下 JSON 格式回复，不要包含其他内容：\n";
    /** 要求 LLM 输出 JSON 数组的尾缀。 */
    public static final String JSON_ARRAY_ONLY =
            "请严格按 JSON 数组格式回复，不要包含其他内容：\n";
    /** 要求 LLM 只输出数字。 */
    public static final String NUMBER_ONLY =
            "只输出数字，不要包含任何其他文字或标点。\n";

    /** 任务关键词列表 — 用于判断用户输入是否为需要执行的任务（vs 简单对话）。 */
    public static final List<String> TASK_KEYWORDS = List.of(
            // 中文动词
            "分析", "处理", "生成", "创建", "执行", "查找", "计算", "翻译", "总结",
            "提取", "转换", "合并", "拆分", "比较", "统计", "预测", "优化",
            "评估", "评价", "检查", "审查", "审核", "了解", "说明", "描述",
            "推荐", "建议", "规划", "方案", "策略", "思路",
            "开发", "研发", "构建", "搭建", "配置", "调试", "修复",
            "写", "改", "删", "查", "修", "重构", "设计", "实现", "测试", "部署",
            // 英文动词
            "analyze", "process", "generate", "create", "execute", "find", "calculate",
            "translate", "summarize", "extract", "convert", "merge", "split", "compare",
            "write", "edit", "delete", "query", "fix", "refactor", "design", "implement",
            "test", "deploy", "evaluate", "review", "check", "plan", "build", "develop");

    /** 编排者通用人格前缀，供各模式 system prompt 复用。 */
    public static final String ORCHESTRATOR_PERSONA =
            "你是灵枢（Sivan），用户的 AI 智能体团队协调者。你负责调度和管理多个 AI 智能体（Agent）协作完成任务。";

    /** 五种编排执行模式说明，通用于阶段间（Squad.mode）和阶段内（PhaseNode.mode）两层。 */
    public static final String MODE_DESCRIPTION =
            "编排模式决定成员间的配合方式，共五种：\n" +
            "SEQUENTIAL   — 顺序串行：依次执行，前一输出作为后一输入。适合有先后依赖的流水线。\n" +
            "PARALLEL     — 并行独立：所有成员同时执行同一任务，结果合并。适合多角度并行分析。\n" +
            "CONDITIONAL  — 条件路由：根据上一阶段输出的语义判断是否触发本阶段。适合可选/分支步骤。\n" +
            "HIERARCHICAL — 层级分解：管理者将任务拆解为子任务 JSON，Worker 按依赖 DAG 拓扑序执行。适合复杂多步骤任务。\n" +
            "CONSENSUS    — 共识决策：所有成员独立求解，综合评估一致性；低置信度时启动第二轮分歧消解。适合需多方确认的判断型任务。\n";

    // ============================================================
    // 安全转义
    // ============================================================

    /** 用户输入安全转义，防止提示词注入。 */
    public static String escapeUserInput(String input) {
        if (input == null) return "";
        return input
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[-]{3,}", "—")
                .replaceAll("\\[/?\\w+\\]", "")
                .replaceAll("\\|\\|", "");
    }

    /** 若输入超过最大长度则截断。 */
    public static String truncate(String input, int maxLen) {
        if (input == null) return "";
        if (input.length() <= maxLen) return input;
        return input.substring(0, maxLen) + "...(truncated)";
    }

    // ============================================================
    // 调用统计
    // ============================================================

    private static final ConcurrentHashMap<String, AtomicLong> CALL_COUNTS = new ConcurrentHashMap<>();

    public static long recordCall(String templateName) {
        return CALL_COUNTS.computeIfAbsent(templateName, k -> new AtomicLong(0)).incrementAndGet();
    }
}
