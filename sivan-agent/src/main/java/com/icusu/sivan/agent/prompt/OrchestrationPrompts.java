package com.icusu.sivan.agent.prompt;

import java.util.List;
import java.util.Map;

/**
 * 编排执行提示词。统一以「灵枢（Sivan）」为唯一人格，覆盖五种编排模式在两个维度的应用。
 *
 * <h3>五种编排模式</h3>
 * <ul>
 *   <li>SEQUENTIAL   — 顺序串行：阶段间依次调度各阶段；阶段内依次执行 Agent</li>
 *   <li>PARALLEL     — 并行独立：阶段间并行调度；阶段内 Agent 并发执行</li>
 *   <li>CONDITIONAL  — 条件路由：阶段间 LLM 决策下一阶段；阶段内 Embedding 语义匹配</li>
 *   <li>HIERARCHICAL — 层级分解：管理者拆解任务 → Worker 按 DAG 执行子任务</li>
 *   <li>CONSENSUS    — 共识决策：阶段间多阶段结果综合；阶段内多 Agent 共识</li>
 * </ul>
 *
 * <h3>缓存友好设计（O3 合并优化）</h3>
 * 六个独立的 STATIC prompt 合并为 {@link #ORCHESTRATION_SYSTEM} 一个，达到缓存阈值 ~600 tokens。
 * 旧常量保留为 {@code @Deprecated} 别名保持编译兼容。
 * 可变数据通过 User prompt 注入。
 */
public final class OrchestrationPrompts {

    private OrchestrationPrompts() {}

    // ============================================================
    // 共享常量
    // ============================================================

    /** 编排模式完整说明，供 Topology 生成等场景复用。 */
    public static final String MODE_OVERVIEW = PromptUtils.MODE_DESCRIPTION;

    // ============================================================
    // O3 合并编排 system prompt（六合一）
    // ============================================================

    /**
     * 合并的编排 system prompt。五种执行模式定义共存于一个 STATIC prompt 中，
     * 配合基座 CHAT_SYSTEM 可触发前缀缓存（~600 tokens）。
     * <p>
     * 各策略（SEQUENTIAL/PARALLEL/CONDITIONAL/HIERARCHICAL/CONSENSUS）统一使用此 system prompt，
     * 通过 User prompt 中指定的 mode 字段决定当前执行模式。
     */
    public static final Prompt ORCHESTRATION_SYSTEM = new Prompt(
            PromptUtils.ORCHESTRATOR_PERSONA + "\n\n" +
            "根据当前阶段的 mode 字段，你将以对应的编排模式执行：\n\n" +

            "### SEQUENTIAL — 顺序串行\n" +
            "核心原则：成员按数组顺序执行，前一输出传递给后一作为输入。\n" +
            "第一成员输入来自上游输出或初始任务。\n" +
            "当前成员完成时，其输出必须在思维中记录并传递给下一成员。\n" +
            "只输出任务结果，不添加角色前缀或额外说明。\n\n" +

            "### PARALLEL — 并行独立\n" +
            "核心原则：所有成员同时独立执行同一任务，结果合并。\n" +
            "适合多角度分析、多文件并行修改、独立子任务。\n" +
            "请从自身专业角度分析并输出结果，无需考虑其他成员的输出。\n" +
            "只输出任务结果，不添加角色前缀或额外说明。\n\n" +

            "### CONDITIONAL — 条件路由\n" +
            "核心原则：根据前序阶段输出判断当前阶段是否执行。\n" +
            "条件字段满足则执行，否则跳过。\n" +
            "当用于阶段间调度决策时，所有阶段执行完毕时输出 -1，只输出数字编号。\n\n" +

            "### HIERARCHICAL — 层级分解\n" +
            "管理者将复杂任务拆解为结构化子任务列表（JSON），\n" +
            "后续成员按 DAG 拓扑序执行子任务。\n" +
            "每个子任务需包含 id、goal、input、expected_output、depends_on。\n" +
            "管理者输出 JSON，执行者专注当前子任务。\n\n" +

            "### CONSENSUS — 共识决策\n" +
            "核心原则：多个成员独立求解后综合评估，取多数一致结果。\n" +
            "首轮各成员独立输出意见 → 置信度不足时第二轮分歧消解 → 第三轮收敛结论。\n" +
            "综合分析多个成员的独立输出，提取共识点、识别分歧内容，给出综合置信度评分。\n" +
            "意见分歧时需明确指出分歧方和分歧内容。\n" +
            "适合代码审查、架构评审、风险评估等需要多方确认的判断型任务。",
            Prompt.CacheStrategy.STATIC, 550, Prompt.OutputFormat.FREE_TEXT);

    // ============================================================
    // CONDITIONAL — 条件路由（User prompt）
    // ============================================================

    /** 构建条件路由决策的 user prompt。 */
    public static Prompt conditionalRouteUser(List<PhaseInfo> phases, int currentIndex, String output) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("当前阶段已执行完毕，判断下一步应执行哪个阶段：\n\n可选阶段：\n");
        for (int j = currentIndex + 1; j < phases.size(); j++) {
            var p = phases.get(j);
            sb.append("[").append(j).append("] ").append(p.name());
            if (p.description() != null && !p.description().isBlank()) {
                sb.append(" — ").append(p.description());
            }
            sb.append("\n");
        }
        sb.append("\n当前阶段输出摘要：\n");
        sb.append(PromptUtils.truncate(output, 300));
        sb.append("\n\n").append(PromptUtils.NUMBER_ONLY);
        String content = sb.toString();
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                50 + output.length() / 3, Prompt.OutputFormat.SINGLE_NUMBER);
    }

    /** 阶段信息投影。 */
    public record PhaseInfo(String name, String description) {}

    // ============================================================
    // HIERARCHICAL — 层级分解（User prompt）
    // ============================================================

    /** 构建任务分解的 user prompt。 */
    public static Prompt hierarchicalDecomposeUser(String taskDescription) {
        String content = "请将以下任务拆解为子任务列表：\n\n" +
                "## 任务\n" + PromptUtils.truncate(taskDescription, 8000) + "\n\n" +
                "## JSON 格式\n" +
                "```json\n" +
                "{\n" +
                "  \"subtasks\": [\n" +
                "    {\n" +
                "      \"id\": 1,\n" +
                "      \"goal\": \"子任务目标\",\n" +
                "      \"input\": \"子任务输入/上下文\",\n" +
                "      \"expected_output\": \"期望产出描述\",\n" +
                "      \"depends_on\": []\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": 2,\n" +
                "      \"goal\": \"另一子任务\",\n" +
                "      \"input\": \"子任务输入\",\n" +
                "      \"expected_output\": \"期望产出\",\n" +
                "      \"depends_on\": [1]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "```";
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                130 + taskDescription.length() / 3, Prompt.OutputFormat.JSON_OBJECT);
    }

    /** 构建子任务执行的 user prompt。 */
    public static Prompt subTaskUser(String goal, String input, String expectedOutput,
                                      String depSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 子任务目标\n").append(goal).append("\n\n");
        if (input != null && !input.isBlank()) {
            sb.append("## 输入上下文\n").append(input).append("\n\n");
        }
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            sb.append("## 期望产出\n").append(expectedOutput).append("\n\n");
        }
        if (depSummary != null && !depSummary.isBlank()) {
            sb.append(depSummary);
        }
        String content = sb.toString().strip();
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                content.length() / 3, Prompt.OutputFormat.FREE_TEXT);
    }

    // ============================================================
    // CONSENSUS — 共识决策（User prompt）
    // ============================================================

    /** 构建共识综合的 user prompt。 */
    public static Prompt consensusSynthesisUser(Map<String, String> agentResults) {
        StringBuilder sb = new StringBuilder(
                "以下是多个智能体对同一任务的独立回答，请综合分析：\n\n");
        int totalLen = 0;
        for (var entry : agentResults.entrySet()) {
            String v = entry.getValue();
            sb.append("【").append(entry.getKey()).append("】\n").append(v).append("\n\n");
            totalLen += v.length();
        }
        return new Prompt(sb.toString(), Prompt.CacheStrategy.DYNAMIC,
                30 + totalLen / 3, Prompt.OutputFormat.JSON_OBJECT);
    }

    /** 共识第二轮分歧消解的 user prompt。 */
    public static Prompt consensusSecondRoundUser(String originalTask, String majorityOpinion,
                                                   String dissentPoints, String previousAnswer) {
        String content = "第一轮共识未达成，请重新审视以下分歧：\n\n" +
                "## 原始任务\n" + originalTask + "\n\n" +
                "## 多数方意见\n" + (majorityOpinion != null ? majorityOpinion : "（无）") + "\n\n" +
                "## 分歧点\n" + (dissentPoints != null && !dissentPoints.isEmpty()
                        ? dissentPoints : "（无明确分歧）") + "\n\n" +
                "## 你在第一轮的答案\n" + previousAnswer + "\n\n" +
                "请基于上述分歧点重新评估。如修正原答案需在最终输出中说明修正理由；\n" +
                "如坚持原答案也请说明坚持理由。";
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                50 + content.length() / 3, Prompt.OutputFormat.FREE_TEXT);
    }

    /** 阶段间共识综合 user prompt（Squad 级：多个阶段输出合并为最终结论）。 */
    public static Prompt consensusInterPhaseUser(List<Map.Entry<String, String>> phaseResults) {
        StringBuilder sb = new StringBuilder(
                "以下是多个执行阶段对同一任务的输出，请综合得出最终结论：\n\n");
        int totalLen = 0;
        for (var entry : phaseResults) {
            String v = entry.getValue();
            sb.append("【").append(entry.getKey()).append("】\n").append(v).append("\n\n");
            totalLen += v.length();
        }
        return new Prompt(sb.toString(), Prompt.CacheStrategy.DYNAMIC,
                30 + totalLen / 3, Prompt.OutputFormat.FREE_TEXT);
    }

    // ============================================================
    // 通用 — 阶段任务执行
    // ============================================================

    /** 构建通用阶段任务的 user prompt。 */
    public static Prompt phaseTaskUser(String input, String description) {
        String content = "## 当前任务\n" + input + "\n\n" +
                "## 要求\n" + (description != null ? description : "请根据上述任务继续处理。");
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                15 + input.length() / 2, Prompt.OutputFormat.FREE_TEXT);
    }
}
