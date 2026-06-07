package com.icusu.sivan.orch.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder.RecordRequest;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadFactory;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Stream;

/**
 * Squad 自动创建器：分析任务 → 生成拓扑 → 智能体匹配/创建 → 技能创建 → 持久化。
 * 通过 sink 发射步骤事件，返回创建的 Squad。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SquadCreator {

    private final TopologyGenerator topologyGenerator;
    private final AgentSkillMatcher agentSkillMatcher;
    private final AgentAutoCreator agentAutoCreator;
    private final SkillAutoCreator skillAutoCreator;
    private final IAgentRepository agentRepository;
    private final ISquadRepository squadRepository;
    private final InstinctPatternService instinctPatternService;
    private final RoutingDecisionRecorder routingDecisionRecorder;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 创建 Squad 全链路：分析 → 拓扑生成 → 智能体匹配/创建 → 技能 → 持久化。 */
    public Mono<Squad> createSquadStream(String taskDescription, UUID accountId, UUID projectId,
                                         UUID conversationId, FluxSink<OrchestrationEvent> sink) {
        return createSquadStream(taskDescription, accountId, projectId, conversationId, sink, null);
    }

    /** 创建 Squad 全链路（带上下文感知）。 */
    public Mono<Squad> createSquadStream(String taskDescription, UUID accountId, UUID projectId,
                                         UUID conversationId, FluxSink<OrchestrationEvent> sink,
                                         String historyContext) {
        sink.next(OrchestrationEvent.stepStart("analyze", "正在分析任务并规划编排方案..."));

        return topologyGenerator.inferSquadMeta(accountId, taskDescription, historyContext)
                .flatMap(meta -> {
                    sink.next(OrchestrationEvent.stepEnd("analyze", "任务分析完成"));

                    // 能力缺口分析：将已有智能体按任务匹配度排序，供拓扑 LLM 优先复用
                    sink.next(OrchestrationEvent.stepStart("capability_analyze", "正在分析已有智能体能力覆盖..."));
                    TopologyGenerator.CapabilityGap gap = topologyGenerator.analyzeCapabilityGap(accountId, taskDescription);
                    sink.next(OrchestrationEvent.stepEnd("capability_analyze",
                            gap.matchedAgents().size() + " 个智能体可复用",
                            Map.of("matched", gap.matchedAgents().size())));

                    sink.next(OrchestrationEvent.stepStart("generate_topology", "正在生成执行阶段拓扑..."));
                    return topologyGenerator.generateForNewSquad(accountId, taskDescription, gap)
                            .flatMap(topology -> {
                                try {
                                    validateTopology(topology);
                                } catch (IllegalArgumentException validationErr) {
                                    log.warn("拓扑验证失败: {}", validationErr.getMessage());
                                    sink.next(OrchestrationEvent.complete(Map.of(
                                            "type", "chat",
                                            "content", toUserMessage("自动创建 Squad 失败", validationErr))));
                                    sink.complete();
                                    return Mono.empty();
                                }
                                List<PhaseNode> phases = topology.getPhases();
                                if (phases == null || phases.isEmpty()) {
                                    sink.next(OrchestrationEvent.complete(Map.of(
                                            "type", "chat",
                                            "content", "无法为此任务生成有效的编排阶段，请稍后重试。")));
                                    sink.complete();
                                    return Mono.empty();
                                }
                                sink.next(OrchestrationEvent.stepEnd("generate_topology",
                                        "生成 " + phases.size() + " 个执行阶段", Map.of("phaseCount", phases.size())));

                                return matchAndCreate(phases, taskDescription, meta.taskType(), meta.name(),
                                        meta.description(), topology.getMode(), topology.getPatternId(),
                                        accountId, projectId, conversationId, sink);
                            });
                })
                .onErrorResume(e -> {
                    log.error("自动创建 Squad 失败", e);
                    routingDecisionRecorder.record(RecordRequest.simple(
                            accountId, projectId, conversationId, taskDescription,
                            "SQUAD_AUTO_CREATE", "ERROR", false,
                            "创建 Squad 失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"), null));
                    sink.next(OrchestrationEvent.complete(Map.of(
                            "type", "chat",
                            "content", toUserMessage("创建 Squad 失败", e))));
                    sink.complete();
                    return Mono.empty();
                });
    }

    private Mono<Squad> matchAndCreate(List<PhaseNode> phases, String taskDescription,
                                        String taskType, String name, String description, SquadMode mode,
                                        UUID sourcePatternId,
                                        UUID accountId, UUID projectId, UUID conversationId,
                                        FluxSink<OrchestrationEvent> sink) {
        // 智能体能力匹配
        sink.next(OrchestrationEvent.stepStart("match_agent", "正在匹配已有智能体..."));
        Set<String> matchedRoleNames = new HashSet<>();
        for (PhaseNode phase : phases) {
            if (phase.getAgents() == null || phase.getAgents().isEmpty()) continue;
            String capabilityReq = (phase.getDescription() != null ? phase.getDescription() : "")
                    + " " + (phase.getName() != null ? phase.getName() : "");
            if (capabilityReq.isBlank()) capabilityReq = taskDescription;

            AgentSkillMatcher.MatchResult result = agentSkillMatcher.match(capabilityReq, accountId);
            if (result.isMatched()) {
                phase.getAgents().set(0, result.getAgent().getAgentName());
                matchedRoleNames.add(result.getAgent().getAgentName());
            }
        }
        sink.next(OrchestrationEvent.stepEnd("match_agent",
                "已匹配 " + matchedRoleNames.size() + " 个已有智能体", Map.of("matched", matchedRoleNames.size())));

        List<String> agentNames = phases.stream()
                .flatMap(p -> p.getAgents() != null ? p.getAgents().stream() : Stream.empty())
                .filter(n -> !matchedRoleNames.contains(n))
                .distinct()
                .toList();

        Map<String, String> agentPhaseContext = new HashMap<>();
        for (PhaseNode phase : phases) {
            if (phase.getAgents() != null) {
                String ctx = phase.getName() != null ? phase.getName() : "";
                for (String agent : phase.getAgents()) {
                    if (!matchedRoleNames.contains(agent)) {
                        agentPhaseContext.merge(agent, ctx, (a, b) -> a + "; " + b);
                    }
                }
            }
        }

        if (!agentNames.isEmpty()) {
            return createAgentsAndSkills(agentNames, taskType, agentPhaseContext, accountId, projectId, sink)
                    .then(Mono.defer(() -> Mono.just(saveSquad(accountId, name, description, mode != null ? mode : SquadMode.SEQUENTIAL,
                            phases, projectId, conversationId, taskDescription, sourcePatternId))));
        }

        return Mono.just(saveSquad(accountId, name, description, mode != null ? mode : SquadMode.SEQUENTIAL,
                phases, projectId, conversationId, taskDescription, sourcePatternId));
    }

    private Mono<Void> createAgentsAndSkills(List<String> agentNames, String taskType,
                                              Map<String, String> phaseContext,
                                              UUID accountId, UUID projectId,
                                              FluxSink<OrchestrationEvent> sink) {
        sink.next(OrchestrationEvent.stepStart("create_agent",
                "正在创建 " + agentNames.size() + " 个新智能体..."));
        return Flux.fromIterable(agentNames)
                .flatMap(name -> agentAutoCreator.create(name, taskType, phaseContext.get(name), accountId, projectId))
                .then(Mono.defer(() -> {
                    sink.next(OrchestrationEvent.stepEnd("create_agent", "创建 " + agentNames.size() + " 个智能体完成"));
                    sink.next(OrchestrationEvent.stepStart("create_skill", "正在为智能体创建技能..."));

                    List<AgentDefinition> agentsToSkill = agentNames.stream()
                            .map(name -> agentRepository.findByAccountAndName(accountId, name).orElse(null))
                            .filter(Objects::nonNull)
                            .toList();
                    return Flux.fromIterable(agentsToSkill)
                            .flatMap(agent -> skillAutoCreator.createForAgent(agent, accountId, projectId))
                            .then(Mono.defer(() -> {
                                sink.next(OrchestrationEvent.stepEnd("create_skill", "技能创建完成"));
                                return Mono.empty();
                            }));
                }));
    }

    private Squad saveSquad(UUID accountId, String name, String description, SquadMode mode,
                             List<PhaseNode> phases, UUID projectId, UUID conversationId,
                             String taskDescription, UUID sourcePatternId) {
        Squad squad = SquadFactory.createSystemSquad(accountId, name, description, mode, phases, sourcePatternId);
        squadRepository.save(squad);
        instinctPatternService.freeze(accountId, serializePhasesToJson(phases), generateFeatureVector(name, description, mode, phases, taskDescription), "SQUAD");
        routingDecisionRecorder.record(RecordRequest.simple(
                accountId, projectId, conversationId, taskDescription,
                "SQUAD_AUTO_CREATE", name, true,
                "创建 " + phases.size() + " 阶段 Squad", null));
        return squad;
    }

    // ====== 内部工具 ======

    /** 将阶段列表序列化为 JSON 数组字符串。 */
    static String serializePhasesToJson(List<PhaseNode> phases) {
        if (phases == null || phases.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(phases);
        } catch (Exception e) {
            log.error("序列化阶段列表失败", e);
            return "[]";
        }
    }

    /**
     * 从 Squad 信息生成特征向量，用于本能模板匹配。
     * 根据阶段数量和模式推断复杂度与依赖类型，根据描述推断领域。
     */
    private static PatternFeatureVector generateFeatureVector(String name, String description, SquadMode mode,
                                                               List<PhaseNode> phases, String taskDescription) {
        // 复杂度：阶段数 1→LEVEL_3, 2-3→LEVEL_4, 4+→LEVEL_5
        TaskFeatures.Complexity complexity = TaskFeatures.Complexity.LEVEL_4;
        int phaseCount = phases != null ? phases.size() : 1;
        if (phaseCount <= 1) complexity = TaskFeatures.Complexity.LEVEL_3;
        else if (phaseCount >= 4) complexity = TaskFeatures.Complexity.LEVEL_5;

        // 依赖类型：根据 Squad 模式映射
        TaskFeatures.Dependency dependency = TaskFeatures.Dependency.SEQUENTIAL;
        if (mode != null) {
            dependency = switch (mode) {
                case PARALLEL -> TaskFeatures.Dependency.PARALLEL;
                case CONDITIONAL -> TaskFeatures.Dependency.CONDITIONAL;
                default -> TaskFeatures.Dependency.SEQUENTIAL;
            };
        }

        // 领域推断：从描述和任务描述中提取关键词
        TaskFeatures.Domain domain = TaskFeatures.Domain.GENERAL;
        String text = (description != null ? description : "") + " " + (taskDescription != null ? taskDescription : "");
        String lower = text.toLowerCase();
        if (lower.contains("code") || lower.contains("编程") || lower.contains("开发")
                || lower.contains("bug") || lower.contains("debug") || lower.contains("编码")) {
            domain = TaskFeatures.Domain.CODING;
        } else if (lower.contains("写") || lower.contains("文章") || lower.contains("文案")
                || lower.contains("写作")) {
            domain = TaskFeatures.Domain.WRITING;
        } else if (lower.contains("分析") || lower.contains("数据") || lower.contains("统计")
                || lower.contains("research") || lower.contains("调研")) {
            domain = TaskFeatures.Domain.ANALYSIS;
        } else if (lower.contains("创意") || lower.contains("设计") || lower.contains("brainstorm")) {
            domain = TaskFeatures.Domain.CREATIVE;
        }

        TaskFeatures features = new TaskFeatures(complexity, dependency,
                TaskFeatures.InputStructure.FREE_TEXT, domain, TaskFeatures.OutputType.LONG_TEXT);
        return PatternFeatureVector.fromTaskFeatures(features);
    }

    /** 验证 LLM 生成的拓扑是否合法。 */
    static void validateTopology(TopologyResult topology) {
        if (topology == null) throw new IllegalArgumentException("拓扑生成为空");
        List<PhaseNode> phases = topology.getPhases();
        if (phases == null || phases.isEmpty()) throw new IllegalArgumentException("拓扑无有效阶段");
        for (PhaseNode p : phases) {
            if (p.getAgents() == null) continue;
            for (String agentName : p.getAgents()) {
                if (!isSafeAgentName(agentName)) {
                    throw new IllegalArgumentException("拓扑包含非法 Agent 名称: " + agentName);
                }
            }
        }
    }

    static boolean isSafeAgentName(String name) {
        return name != null && name.length() <= 64 && name.matches("[\\w\\u4e00-\\u9fff\\-_.]+");
    }

    /** 将异常转换为用户友好的错误消息。 */
    static String toUserMessage(String fallbackPrefix, Throwable e) {
        if (e == null) return fallbackPrefix + "：未知错误";
        if (e instanceof DataIntegrityViolationException) {
            return fallbackPrefix + "：数据冲突，请稍后重试";
        }
        String msg = e.getMessage();
        if (msg == null) return fallbackPrefix + "：未知错误";
        if (msg.contains("could not execute batch") || msg.contains("Batch entry")
                || msg.contains("could not execute statement") || msg.contains("SQL")
                || msg.contains("constraint") || msg.contains("violation")) {
            return fallbackPrefix + "：数据异常，请稍后重试";
        }
        if (msg.contains("Connection refused") || msg.contains("connect timed out")
                || msg.contains("Failed to connect") || msg.contains("Host")
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return fallbackPrefix + "：服务暂时不可用，请稍后重试";
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized")
                || msg.contains("Forbidden") || msg.contains("API key")
                || msg.contains("api_key") || msg.contains("Bearer")) {
            return fallbackPrefix + "：服务认证失败，请检查配置";
        }
        return fallbackPrefix + "：服务异常，请稍后重试";
    }
}
