package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.domain.orchestration.Contract;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import com.icusu.sivan.domain.context.ContextTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Squad 执行阶段树，负责将已完成阶段折叠为摘要、保留运行中阶段原文。
 * <p>
 * 供编排路径使用：HistoryCompressor → ContextBuilder(build with SquadTree) → SquadOrchestrator
 */
public class SquadTree implements ContextTree {

    private static final Logger log = LoggerFactory.getLogger(SquadTree.class);

    private Squad squad;
    private SquadExecution execution;
    private List<Contract> contracts;
    /** 预计算的本树 token 估算 */
    private int cachedTokens;

    public SquadTree() {
        this.cachedTokens = 0;
    }

    /** 注入 Squad 定义。 */
    public SquadTree withSquad(Squad squad) {
        this.squad = squad;
        return this;
    }

    /** 注入执行记录。 */
    public SquadTree withExecution(SquadExecution execution) {
        this.execution = execution;
        return this;
    }

    /** 注入阶段间通信契约。 */
    public SquadTree withContracts(List<Contract> contracts) {
        this.contracts = contracts;
        return this;
    }

    @Override
    public String treeType() {
        return "squad";
    }

    @Override
    public String buildContext(String scene, int maxTokens) {
        if (squad == null || execution == null) {
            return "";
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## Squad 执行状态\n\n");
            sb.append("Squad: ").append(squad.getName() != null ? squad.getName() : "未命名").append("\n");
            sb.append("状态: ").append(execution.getStatus()).append("\n");
            sb.append("任务: ").append(truncateStr(execution.getTaskDescription(), 200)).append("\n\n");

            if (squad.getPhases() == null || squad.getPhases().isEmpty()) {
                return sb.toString();
            }

            int currentPhase = execution.getCurrentPhase() != null ? execution.getCurrentPhase() : -1;

            sb.append("### 执行阶段\n\n");

            for (int i = 0; i < squad.getPhases().size(); i++) {
                var phaseNode = squad.getPhases().get(i);
                boolean isRunning = (i == currentPhase || (currentPhase < 0 && i == 0));
                boolean isCompleted = i < currentPhase;

                String phaseLabel = phaseNode.getName() != null ? phaseNode.getName() : ("阶段 " + i);
                String phaseDesc = phaseNode.getDescription();

                if (isCompleted) {
                    // 已完成阶段：折叠为摘要
                    String phaseSummary = findPhaseSummary(i);
                    sb.append("- ").append(phaseLabel);
                    if (phaseSummary != null && !phaseSummary.isBlank()) {
                        sb.append(": ").append(truncateStr(phaseSummary, 150));
                    } else {
                        sb.append(": 已完成");
                    }
                    sb.append("\n");
                } else if (isRunning) {
                    // 运行中阶段：展开
                    sb.append("- **").append(phaseLabel).append("** (执行中)");
                    if (phaseDesc != null && !phaseDesc.isBlank()) {
                        sb.append(": ").append(phaseDesc);
                    }
                    sb.append("\n");

                    // 展开该阶段内 agent 的契约内容
                    List<Contract> phaseContracts = getContractsForPhase(i);
                    for (Contract c : phaseContracts) {
                        sb.append("  - ").append(c.getSourceAgent()).append(" → ").append(c.getTargetAgent()).append(": ");
                        sb.append(truncateStr(c.getContent(), 300)).append("\n");
                    }
                } else {
                    // 未执行阶段：简要显示
                    sb.append("- ").append(phaseLabel).append(" (待执行)");
                    if (phaseDesc != null && !phaseDesc.isBlank()) {
                        sb.append(": ").append(phaseDesc);
                    }
                    sb.append("\n");
                }
            }

            // 检查预算，必要时进一步折叠
            String result = sb.toString().trim();
            cachedTokens = estimateTokenCount(result);

            if (cachedTokens > maxTokens && squad.getPhases().size() > 2) {
                // 预算紧张时，将所有阶段折叠到最简形式
                return buildCompactContext(maxTokens);
            }

            return result;

        } catch (Exception e) {
            log.warn("SquadTree 构建异常", e);
            return "## Squad 执行状态\n\n" + (squad.getName() != null ? squad.getName() : "") + "\n";
        }
    }

    /** 预算紧张时的紧凑模式：所有阶段一行摘要。 */
    private String buildCompactContext(int maxTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Squad 执行\n\n");
        sb.append("Squad: ").append(squad.getName() != null ? squad.getName() : "").append("\n");
        sb.append("阶段: ").append(squad.getPhases().size()).append(" 个, ");
        sb.append("当前阶段: ");
        int currentPhase = execution.getCurrentPhase() != null ? execution.getCurrentPhase() : 0;
        if (currentPhase < squad.getPhases().size()) {
            sb.append(squad.getPhases().get(currentPhase).getName() != null
                    ? squad.getPhases().get(currentPhase).getName() : "阶段 " + currentPhase);
        }
        sb.append("\n");

        // 每个阶段一行
        for (int i = 0; i < squad.getPhases().size(); i++) {
            var phase = squad.getPhases().get(i);
            boolean isRunning = (i == currentPhase || (currentPhase < 0 && i == 0));
            sb.append("- ").append(isRunning ? "▶ " : "  ");
            sb.append(phase.getName() != null ? phase.getName() : "阶段 ").append(i);
            sb.append("\n");
        }

        String result = sb.toString().trim();
        cachedTokens = estimateTokenCount(result);
        return result;
    }

    /** 查找指定阶段的契约列表。 */
    private List<Contract> getContractsForPhase(int phaseIndex) {
        if (contracts == null) return List.of();
        return contracts.stream()
                .filter(c -> c.getPhase() != null && c.getPhase() == phaseIndex)
                .toList();
    }

    /** 从契约中提取指定阶段的执行摘要。 */
    private String findPhaseSummary(int phaseIndex) {
        if (contracts == null) return null;
        List<Contract> phaseContracts = getContractsForPhase(phaseIndex);
        if (phaseContracts.isEmpty()) return null;

        // 取最后一条契约的内容作为阶段摘要
        Contract last = phaseContracts.get(phaseContracts.size() - 1);
        if (last.getContent() != null && !last.getContent().isBlank()) {
            return last.getContent();
        }
        return null;
    }

    @Override
    public int estimateTokens() {
        return cachedTokens;
    }

    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }

    private static String truncateStr(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
