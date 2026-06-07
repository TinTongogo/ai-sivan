package com.icusu.sivan.agent.prompt;

/**
 * Squad 拓扑生成与命名提示词。统一以「灵枢（Sivan）」为唯一人格。
 *
 * <h3>O4 缓存优化</h3>
 * {@link #TOPOLOGY_GENERATE_SYSTEM_STATIC} 为纯静态 system prompt（不含 Agent 列表），
 * 可缓存前缀。Agent 列表等动态内容通过 {@link #topologyGenerateUser} 注入。
 */
public final class SquadPrompts {

    private SquadPrompts() {}

    // ============================================================
    // 拓扑生成
    // ============================================================

    /** 拓扑生成 system prompt（纯静态，不含 agents 列表和任务描述）。 */
    public static final Prompt TOPOLOGY_GENERATE_SYSTEM_STATIC = new Prompt(
            "你是灵枢（Sivan），负责将用户任务分解为多阶段的 Squad 执行拓扑。\n\n" +
            OrchestrationPrompts.MODE_OVERVIEW + "\n" +
            "模式选择指导（为每个 phase 的 mode 字段选择最合适的模式）：\n" +
            "- SEQUENTIAL（默认）：阶段内有多个 Agent 时有先后依赖，前一输出作为后一输入。适合流水线型工作。\n" +
            "- PARALLEL：阶段内多个 Agent 可同时独立执行，结果合并。适合多角度并行分析。\n" +
            "- CONDITIONAL：本阶段是否执行取决于上一阶段输出的条件判断。适合可选步骤/分支处理。\n" +
            "- HIERARCHICAL：阶段内第一个 Agent 负责规划分解任务，后续 Agent 按 DAG 执行子任务。适合复杂任务。\n" +
            "- CONSENSUS：阶段内多个 Agent 独立求解后综合评估，取多数一致结果。适合需要多方确认的判断型任务。\n\n" +
            "设计原则：\n" +
            "1. 根据任务特点为每个阶段选择合适的 mode，通常 2-4 个阶段\n" +
            "2. 为每个阶段设计通用的智能体角色名（中文，如「代码扫描员」「架构审查员」）\n" +
            "3. phase 从 0 连续编号，每个阶段至少 1 个 Agent\n" +
            "4. 各阶段之间应有清晰的依赖关系，形成合理的执行流水线\n\n" +
            "示例多 Phase 编排（含多种模式）：\n" +
            "[{\"phase\":0,\"name\":\"需求分析\",\"description\":\"分析并定义需求\",\"mode\":\"CONSENSUS\",\"agents\":[{\"name\":\"需求分析师\",\"description\":\"分析用户需求\",\"capability\":\"需求分析\"},{\"name\":\"技术评估师\",\"description\":\"评估技术可行性\",\"capability\":\"技术评估\"}]},{\"phase\":1,\"name\":\"系统设计\",\"description\":\"设计系统架构\",\"mode\":\"HIERARCHICAL\",\"agents\":[{\"name\":\"架构师\",\"description\":\"制定架构方案\",\"capability\":\"架构设计\"},{\"name\":\"模块设计师\",\"description\":\"详细模块设计\",\"capability\":\"模块设计\"}]},{\"phase\":2,\"name\":\"并行开发\",\"description\":\"前后端并行开发\",\"mode\":\"PARALLEL\",\"agents\":[{\"name\":\"前端开发\",\"description\":\"实现前端界面\",\"capability\":\"前端开发\"},{\"name\":\"后端开发\",\"description\":\"实现后端API\",\"capability\":\"后端开发\"}]},{\"phase\":3,\"name\":\"代码审查\",\"description\":\"审查代码质量\",\"mode\":\"CONDITIONAL\",\"agents\":[{\"name\":\"审查员\",\"description\":\"审查代码\",\"capability\":\"代码审查\"}]},{\"phase\":4,\"name\":\"集成测试\",\"description\":\"执行集成测试\",\"mode\":\"SEQUENTIAL\",\"agents\":[{\"name\":\"测试工程师\",\"description\":\"编写和执行测试\",\"capability\":\"测试\"}]}]\n\n" +
            "【严格要求】只输出一个 JSON 数组。第一个字符必须是 [，不包含任何解释或 markdown。",
            Prompt.CacheStrategy.STATIC, 550, Prompt.OutputFormat.JSON_ARRAY);

    /** @deprecated 使用 {@link #TOPOLOGY_GENERATE_SYSTEM_STATIC} */
    @Deprecated
    public static final Prompt TOPOLOGY_GENERATE_SYSTEM = TOPOLOGY_GENERATE_SYSTEM_STATIC;

    /**
     * 构建拓扑生成的 user prompt（DYNAMIC）。可选的 agents 列表注入。
     *
     * @param taskDescription 用户任务描述
     * @param agentContext    格式化后的已有智能体列表（可为 null），格式参考 {@code CapabilityGap.formattedContext()}
     */
    public static Prompt topologyGenerateUser(String taskDescription, String agentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户任务\n").append(taskDescription).append("\n\n");
        if (agentContext != null && !agentContext.isBlank()) {
            sb.append(agentContext).append("\n\n");
        }
        sb.append("请严格按以下 JSON 数组格式回复，第一个字符必须是 [，不包含任何解释或 markdown：\n");
        sb.append("[{\"phase\":0,\"name\":\"阶段名\",\"agents\":[\"agent1\"],\"description\":\"阶段目标\",\"mode\":\"SEQUENTIAL\"}]");
        String content = sb.toString();
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                80 + taskDescription.length() / 2 + (agentContext != null ? agentContext.length() / 3 : 0),
                Prompt.OutputFormat.JSON_ARRAY);
    }

    /** 构建拓扑生成的 user prompt（无已有 Agent 场景）。兼容旧调用。 */
    public static Prompt topologyGenerateUser(String taskDescription) {
        return topologyGenerateUser(taskDescription, null);
    }

    /** @deprecated 使用 {@link #topologyGenerateUser(String, String)}。原方法标记为 STATIC 但实际含动态 agent 列表，无效。
     *  现返回 DYNAMIC user prompt。 */
    @Deprecated
    public static Prompt topologyWithAgents(String agentList) {
        // O4 修复：拆分为 system + user，此方法保留兼容但缓存策略改为 DYNAMIC
        return topologyGenerateUser("（用户任务）", agentList);
    }

    // ============================================================
    // Squad 命名
    // ============================================================

    public static final Prompt SQUAD_NAMING_SYSTEM = new Prompt(
            "你是灵枢（Sivan），负责根据任务描述为新创建的 Squad 团队命名。只输出 JSON。",
            Prompt.CacheStrategy.STATIC, 25, Prompt.OutputFormat.JSON_OBJECT);

    public static Prompt squadNamingUser(String taskDescription) {
        return squadNamingUser(taskDescription, null);
    }

    public static Prompt squadNamingUser(String taskDescription, String historyContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是灵枢（Sivan），根据以下用户任务描述，推断：\n");
        sb.append("1. squadName：适合此任务的 Squad 团队名称（中文，2-6 字）\n");
        sb.append("2. squadDescription：Squad 的职责描述（一段话，不超过 150 字）\n");
        sb.append("3. taskType：任务类型标识（英文小写，下划线分隔，如 novel_writing、code_review）\n\n");
        if (historyContext != null && !historyContext.isBlank()) {
            sb.append("## 对话上下文（用户当前任务基于以下对话）\n")
              .append(historyContext).append("\n\n");
        }
        sb.append(PromptUtils.JSON_ONLY);
        sb.append("{\n");
        sb.append("  \"squadName\": \"团队名称\",\n");
        sb.append("  \"squadDescription\": \"职责描述\",\n");
        sb.append("  \"taskType\": \"task_type\"\n");
        sb.append("}\n\n");
        sb.append("用户任务描述: ").append(taskDescription);
        String content = sb.toString();
        return new Prompt(content, Prompt.CacheStrategy.DYNAMIC,
                120 + content.length() / 2, Prompt.OutputFormat.JSON_OBJECT);
    }
}
