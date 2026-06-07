package com.icusu.sivan.orch.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 目标拆解器 — 先尝试 TopologyGenerator 生成 Squad 拓扑映射里程碑，
 * 无匹配时回退到 LLM 直接分解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoalDecomposer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelRouter modelRouter;
    private final TopologyGenerator topologyGenerator;

    private static final String DECOMPOSE_SYSTEM = """
            你是灵枢（Sivan），负责将用户的目标拆解为可执行的里程碑和任务列表。

            输出格式为纯 JSON 数组：
            [
              {
                "name": "里程碑名称",
                "description": "里程碑描述",
                "tasks": [
                  { "description": "具体任务描述，包含可执行的动作" },
                  { "description": "..." }
                ]
              }
            ]

            要求：
            - 每个里程碑 2-5 个任务
            - 里程碑 3-6 个，不要过多
            - 任务描述要具体可执行，如"创建项目配置文件 pom.xml"、"实现用户登录接口"
            - 任务按依赖顺序排列
            - 只输出 JSON，不要额外说明
            """;

    /**
     * 拆解目标为里程碑和任务。
     * 先尝试 TopologyGenerator 生成 Squad 拓扑，从 Phase 列表映射 Milestone/Task。
     * 无拓扑匹配时回退到 LLM 直接分解。
     */
    public Mono<Goal> decompose(String title, String description, UUID accountId, UUID projectId) {
        // 先尝试 TopologyGenerator 生成 Squad 拓扑
        return topologyGenerator.generateForNewSquad(accountId, description)
                .flatMap(topology -> {
                    List<PhaseNode> phases = topology.getPhases();
                    if (phases != null && !phases.isEmpty()) {
                        log.info("目标拆解使用 Squad 拓扑映射: phases={}", phases.size());
                        List<Milestone> milestones = mapPhasesToMilestones(phases);
                        return Mono.just(buildGoal(title, description, accountId, projectId, milestones));
                    }
                    // 拓扑为空，回退到 LLM
                    return llmDecompose(title, description, accountId, projectId);
                })
                .onErrorResume(e -> {
                    log.warn("TopologyGenerator 生成失败，回退到 LLM 拆解: {}", e.getMessage());
                    return llmDecompose(title, description, accountId, projectId);
                });
    }

    /**
     * 从 Phase 列表映射 Milestone/Task。
     * 每个 Phase 对应一个 Milestone，Phase 内的每个 Agent 对应一个 Task。
     */
    private List<Milestone> mapPhasesToMilestones(List<PhaseNode> phases) {
        List<Milestone> milestones = new ArrayList<>();
        for (int pIdx = 0; pIdx < phases.size(); pIdx++) {
            PhaseNode phase = phases.get(pIdx);
            List<Task> tasks = new ArrayList<>();
            List<String> agentNames = phase.getAgents();
            if (agentNames != null) {
                for (int aIdx = 0; aIdx < agentNames.size(); aIdx++) {
                    tasks.add(Task.builder()
                            .order(aIdx)
                            .taskId(UUID.randomUUID())
                            .description(agentNames.get(aIdx) + "：" + phase.getName())
                            .agentIndex(aIdx)
                            .agentName(agentNames.get(aIdx))
                            .status("pending")
                            .completed(false)
                            .build());
                }
            }
            milestones.add(Milestone.builder()
                    .name(phase.getName() != null ? phase.getName() : "阶段 " + pIdx)
                    .description(phase.getDescription())
                    .order(pIdx)
                    .phaseIndex(pIdx)
                    .phaseMode(phase.getMode() != null ? phase.getMode().name() : "SEQUENTIAL")
                    .tasks(tasks)
                    .build());
        }
        return milestones;
    }

    /**
     * LLM 直接分解目标（回退路径）。
     */
    private Mono<Goal> llmDecompose(String title, String description, UUID accountId, UUID projectId) {
        String userPrompt = "目标标题：" + title + "\n目标描述：" + description;

        return modelRouter.getDefaultModel(accountId).chat(
                        List.of(
                                Msg.of(Role.SYSTEM, DECOMPOSE_SYSTEM),
                                Msg.of(Role.USER, userPrompt)
                        ),
                        Model.ModelParams.defaults()
                )
                .map(response -> {
                    String text = response != null ? response.msg().text() : "";
                    return parseMilestones(text);
                })
                .onErrorReturn(e -> {
                    log.warn("LLM 拆解目标失败: {}", e.getMessage());
                    return true;
                }, List.of())
                .map(milestones -> buildGoal(title, description, accountId, projectId, milestones));
    }

    /** 从 LLM 回复中提取里程碑列表。 */
    private List<Milestone> parseMilestones(String text) {
        if (text == null || text.isBlank()) return List.of();

        try {
            String json = extractJsonArray(text);
            JsonNode root = OBJECT_MAPPER.readTree(json);
            List<Milestone> result = new ArrayList<>();

            int mIdx = 0;
            for (JsonNode mNode : root) {
                String mName = mNode.has("name") ? mNode.get("name").asText("里程碑" + (mIdx + 1)) : "里程碑" + (mIdx + 1);
                String mDesc = mNode.has("description") ? mNode.get("description").asText("") : "";

                List<Task> tasks = new ArrayList<>();
                if (mNode.has("tasks")) {
                    int tIdx = 0;
                    for (JsonNode tNode : mNode.get("tasks")) {
                        String tDesc = tNode.has("description") ? tNode.get("description").asText("任务" + (tIdx + 1)) : "任务" + (tIdx + 1);
                        tasks.add(Task.builder()
                                .order(tIdx)
                                .description(tDesc)
                                .completed(false)
                                .taskId(UUID.randomUUID())
                                .status("pending")
                                .build());
                        tIdx++;
                    }
                }

                result.add(Milestone.builder()
                        .name(mName)
                        .description(mDesc)
                        .order(mIdx)
                        .tasks(tasks)
                        .build());
                mIdx++;
            }
            return result;
        } catch (Exception e) {
            log.warn("解析里程碑 JSON 失败", e);
            return List.of();
        }
    }

    /** 从 LLM 回复中提取 JSON 数组部分。 */
    private static String extractJsonArray(String text) {
        if (text == null || text.isBlank()) return "[]";
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < 0) return "[]";
        return text.substring(start, end + 1);
    }

    /** 构建 Goal 对象（尚未持久化）。 */
    private Goal buildGoal(String title, String description, UUID accountId, UUID projectId, List<Milestone> milestones) {
        if (milestones.isEmpty()) {
            // LLM 拆解失败时创建默认里程碑
            milestones = List.of(Milestone.builder()
                    .name("执行")
                    .description("执行目标")
                    .order(0)
                    .tasks(List.of(Task.builder()
                            .order(0)
                            .description(description)
                            .completed(false)
                            .taskId(UUID.randomUUID())
                            .status("pending")
                            .build()))
                    .build());
        }

        int totalTasks = milestones.stream()
                .mapToInt(m -> m.getTasks() != null ? m.getTasks().size() : 0)
                .sum();

        return Goal.builder()
                .accountId(accountId)
                .projectId(projectId)
                .title(title)
                .description(description)
                .status(GoalStatus.PENDING)
                .autoMode(AutoMode.AUTO)
                .milestones(milestones)
                .currentMilestone(0)
                .totalTasks(totalTasks)
                .completedTasks(0)
                .build();
    }
}
