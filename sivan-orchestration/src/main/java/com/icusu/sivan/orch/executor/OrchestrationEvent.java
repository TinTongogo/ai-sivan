package com.icusu.sivan.orch.executor;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 全响应式编排中的结构化事件，覆盖从历史压缩到 Squad 执行的每一步。
 * <p>
 * 事件类型：
 * <ul>
 *   <li>step_start — 步骤开始，携带 phase 和 message</li>
 *   <li>step_end   — 步骤结束，携带 metadata（如耗时、计数）</li>
 *   <li>final      — 编排完成，metadata 包含完整结果（content/thinking/model/tokens/durationMs）</li>
 *   <li>error      — 编排失败</li>
 * </ul>
 */
@Value
@Builder
public class OrchestrationEvent {

    String type;
    String phase;
    String message;
    Map<String, Object> metadata;

    public static OrchestrationEvent stepStart(String phase, String message) {
        return OrchestrationEvent.builder().type("step_start").phase(phase).message(message).build();
    }

    public static OrchestrationEvent stepStart(String phase, String message, Map<String, Object> metadata) {
        return OrchestrationEvent.builder().type("step_start").phase(phase).message(message).metadata(metadata).build();
    }

    public static OrchestrationEvent stepEnd(String phase, String message) {
        return OrchestrationEvent.builder().type("step_end").phase(phase).message(message).build();
    }

    public static OrchestrationEvent stepEnd(String phase, String message, Map<String, Object> metadata) {
        return OrchestrationEvent.builder().type("step_end").phase(phase).message(message).metadata(metadata).build();
    }

    public static OrchestrationEvent complete(Map<String, Object> metadata) {
        return OrchestrationEvent.builder().type("final").phase("final").message("编排完成").metadata(metadata).build();
    }

    public static OrchestrationEvent error(String message) {
        return OrchestrationEvent.builder().type("error").phase("error").message(message).build();
    }
    /** LLM 回复文本流式块。message 为实时增量文本。 */
    public static OrchestrationEvent stream(String text) {
        return OrchestrationEvent.builder().type("stream").phase("stream").message(text).build();
    }

    /** LLM 思考文本流式块。message 为实时增量思考内容。 */
    public static OrchestrationEvent streamThinking(String text) {
        return OrchestrationEvent.builder().type("stream_thinking").phase("stream").message(text).build();
    }

    /** MCP 工具调用。message 为工具名，metadata 含 args。 */
    public static OrchestrationEvent toolCall(String name, Map<String, Object> args) {
        return OrchestrationEvent.builder().type("tool_call").phase("tool").message(name).metadata(args).build();
    }

    /** MCP 工具执行结果。message 为工具名，metadata 含 success 和 output。 */
    public static OrchestrationEvent toolResult(String name, boolean success, String output) {
        return OrchestrationEvent.builder().type("tool_result").phase("tool").message(name)
                .metadata(Map.of("success", success, "output", output)).build();
    }

    /** 产物信息 — Task 执行完成后 output/ 目录新增文件的元数据。 */
    public record ArtifactInfo(String filePath, String fileType, String summary, long fileSize) {}
}
