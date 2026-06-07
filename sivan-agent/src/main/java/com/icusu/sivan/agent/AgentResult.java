package com.icusu.sivan.agent;

/**
 * Agent 同步执行的结果。
 *
 * @param content      最终回复文本
 * @param thinking     思考过程文本（可能为空）
 * @param modelName    使用的模型名称
 * @param totalTokens  总 Token 消耗
 * @param inputTokens  输入 Token 数
 * @param outputTokens 输出 Token 数
 */
public record AgentResult(
        String content,
        String thinking,
        String modelName,
        int totalTokens,
        int inputTokens,
        int outputTokens
) {
    public static AgentResult empty() {
        return new AgentResult("", "", "", 0, 0, 0);
    }
}
