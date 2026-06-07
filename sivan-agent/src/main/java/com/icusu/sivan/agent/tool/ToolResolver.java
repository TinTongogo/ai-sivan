package com.icusu.sivan.agent.tool;

import java.util.UUID;

/**
 * 工具解析器接口。
 * 根据智能体画像、对话上下文或自动匹配规则，从已注册的 MCP 工具中解析最相关的工具集。
 *
 * <p>能力边界：
 * <ul>
 *   <li>按智能体画像解析工具（systemPrompt + craftDeclaration 语义匹配）</li>
 *   <li>按对话上下文解析工具（Chat 路径使用）</li>
 *   <li>全量工具解析（无智能体场景回退）</li>
 * </ul>
 */
public interface ToolResolver {

    /** 为指定智能体解析工具。 */
    MatchedTools resolveForAgent(String agentName, UUID accountId);

    /** 为 Chat 路径解析工具（带内容语义匹配）。 */
    MatchedTools resolveForChat(String content, UUID accountId);

    /** 为 Chat 路径解析工具（带对话历史上下文）。 */
    MatchedTools resolveForChat(String content, String conversationContext, UUID accountId);

    /** 无智能体场景的全量工具决议。 */
    MatchedTools resolve(UUID accountId);
}
