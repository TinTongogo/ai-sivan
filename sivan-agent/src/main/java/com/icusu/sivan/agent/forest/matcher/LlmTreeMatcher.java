package com.icusu.sivan.agent.forest.matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.domain.forest.port.TreeMatcher;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.MatcherPrompts;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.infra.forest.matcher.DefaultTreeMatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 驱动的树匹配器 — 使用大模型将用户请求分解为多步执行树。
 * <p>
 * 优于启发式 {@link DefaultTreeMatcher} 的关键词匹配，能理解任意复杂请求的语义，
 * 输出结构化分解方案。LLM 调用失败时自动降级到 {@link DefaultTreeMatcher}。
 */
@Component
@Primary
public class LlmTreeMatcher implements TreeMatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmTreeMatcher.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 匹配 markdown 代码块标记 */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```|(\\{.*})");

    private final ModelRouter modelRouter;
    private final DefaultTreeMatcher fallback;

    public LlmTreeMatcher(ModelRouter modelRouter, DefaultTreeMatcher fallback) {
        this.modelRouter = modelRouter;
        this.fallback = fallback;
    }

    @PostConstruct
    void init() {
        log.info("[TreeMatcher] LLM 驱动模式已启用，降级策略: DefaultTreeMatcher");
    }

    @Override
    public Mono<ExecutableNode> match(String input, UUID accountId) {
        if (input == null || input.isBlank()) {
            return Mono.just(new TaskNode(""));
        }

        Model model;
        try {
            model = modelRouter.getDefaultModel(accountId);
        } catch (Exception e) {
            log.warn("[TreeMatcher] 获取模型失败，降级到 DefaultTreeMatcher: {}", e.getMessage());
            return fallback.match(input, accountId);
        }

        List<Msg> messages = List.of(
                Msg.of(Role.SYSTEM, MatcherPrompts.TASK_DECOMPOSE_SYSTEM),
                Msg.of(Role.USER, input)
        );

        return model.chat(messages, List.of(), Model.ModelParams.defaults().withTemperature(0.3))
                .map(response -> {
                    String text = response.msg().text();
                    log.debug("[TreeMatcher] LLM 响应: {}", text);
                    return parseTree(text, input);
                })
                .onErrorResume(e -> {
                    log.warn("[TreeMatcher] LLM 分解失败，降级到 DefaultTreeMatcher: {}", e.getMessage());
                    return fallback.match(input, accountId);
                });
    }

    /**
     * 解析 LLM 输出的 JSON 文本为执行树。
     */
    public ExecutableNode parseTree(String jsonText, String originalInput) {
        String json = extractJson(jsonText);
        if (json == null) {
            log.warn("[TreeMatcher] 无法从 LLM 响应中提取 JSON，使用原始输入作为 TaskNode");
            return new TaskNode(originalInput);
        }

        Map<String, Object> root;
        try {
            root = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[TreeMatcher] JSON 解析失败: {}，使用原始输入作为 TaskNode", e.getMessage());
            return new TaskNode(originalInput);
        }

        String type = root.getOrDefault("type", "single").toString().toLowerCase();
        Object stepsObj = root.get("steps");
        List<Map<String, Object>> steps = parseSteps(stepsObj);

        if (steps == null || steps.isEmpty()) {
            log.debug("[TreeMatcher] 无步骤数据，回退 TaskNode");
            return new TaskNode(originalInput);
        }

        List<TaskNode> taskNodes = steps.stream()
                .map(s -> {
                    String content = s.getOrDefault("content", originalInput).toString();
                    return new TaskNode(content);
                })
                .toList();

        // 单步或 single 类型 → 直接返回 TaskNode
        if ("single".equals(type) || taskNodes.size() == 1) {
            return taskNodes.getFirst();
        }

        // 多步 → 按类型构建 InnerGoalNode
        Mode mode = switch (type) {
            case "parallel" -> Mode.PARALLEL;
            case "conditional" -> Mode.CONDITIONAL;
            case "hierarchical" -> Mode.HIERARCHICAL;
            case "consensus" -> Mode.CONSENSUS;
            default -> Mode.SEQUENTIAL;
        };

        log.info("[TreeMatcher] LLM 分解: type={} steps={} mode={}", type, taskNodes.size(), mode);
        InnerGoalNode goalNode = new InnerGoalNode(mode, taskNodes);
        // 存储 reasoning（如果 LLM 返回了），供执行树展示
        Object reasoning = root.get("reasoning");
        if (reasoning instanceof String s && !s.isBlank()) {
            goalNode.putMetadata("reasoning", s);
        }
        return goalNode;
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串（去掉 markdown 代码块标记）。
     */
    static String extractJson(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text.trim());
        if (matcher.matches()) {
            String group1 = matcher.group(1);
            String group2 = matcher.group(2);
            if (group1 != null) return group1.trim();
            if (group2 != null) return group2.trim();
        }

        // 尝试直接作为 JSON
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        return null;
    }

    /**
     * 解析 steps 字段，兼容 List 和 String 类型。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseSteps(Object stepsObj) {
        if (stepsObj instanceof List<?> list) {
            if (list.isEmpty()) return List.of();
            if (list.getFirst() instanceof Map<?, ?>) {
                return (List<Map<String, Object>>) list;
            }
        }
        return null;
    }
}
