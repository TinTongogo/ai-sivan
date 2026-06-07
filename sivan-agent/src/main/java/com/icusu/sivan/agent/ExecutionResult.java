package com.icusu.sivan.agent;

/**
 * Agent 执行结果（单次 LLM 调用输出快照）。
 *
 * @param content      最终回复文本
 * @param thinking     思考过程文本（可能为空）
 * @param modelName    使用的模型名称
 * @param totalTokens  总 Token 消耗
 * @param inputTokens  输入 Token 数
 * @param outputTokens 输出 Token 数
 */
public record ExecutionResult(
        String content,
        String thinking,
        String modelName,
        int totalTokens,
        int inputTokens,
        int outputTokens
) {
    public static ExecutionResult empty() {
        return new ExecutionResult("", "", "", 0, 0, 0);
    }
}
