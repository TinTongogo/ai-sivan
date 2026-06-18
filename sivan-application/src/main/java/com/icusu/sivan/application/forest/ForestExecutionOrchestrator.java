package com.icusu.sivan.application.forest;

import com.icusu.sivan.application.conversation.PromptContextService;
import com.icusu.sivan.application.service.GroupService;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.domain.forest.tree.node.TaskNode;
import com.icusu.sivan.domain.routing.RouteResult;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import com.icusu.sivan.infra.forest.compression.ForestCompressor;
import com.icusu.sivan.common.NodeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 森林执行编排器 — 执行树构建 + 持久化 + 消息链接。
 * <p>
 * 从 ForestConversationService 拆分，负责执行树全生命周期管理。
 */
@Service
public class ForestExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ForestExecutionOrchestrator.class);

    private final ForestCompressor forestCompressor;
    private final ForestRepository forestRepository;
    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private final GroupService groupService;

    public ForestExecutionOrchestrator(ForestCompressor forestCompressor,
                                       ForestRepository forestRepository,
                                       ForestNodeJpaRepository forestNodeJpaRepository,
                                       GroupService groupService) {
        this.forestCompressor = forestCompressor;
        this.forestRepository = forestRepository;
        this.forestNodeJpaRepository = forestNodeJpaRepository;
        this.groupService = groupService;
    }

    /**
     * 构建并压缩执行树。
     *
     * @param userContent    用户输入内容
     * @param conversation   当前对话
     * @param accountId      账户 ID
     * @param contextLength  上下文长度（token 预算）
     * @return { forest, tree }
     */
    public ForestTreePair buildTree(String userContent, Conversation conversation,
                                    UUID accountId, int contextLength) {
        TaskNode tree = new TaskNode(userContent);
        String projectPath = groupService.getProjectDisplayPath(accountId, conversation.getProjectId());
        Forest forest = new Forest(conversation.getConversationId(), accountId,
                conversation.getProjectId(),
                userContent.length() > 50 ? userContent.substring(0, 47) + "..." : userContent,
                tree.nodeId());
        forestCompressor.compress(forest, tree, "send", contextLength);
        return new ForestTreePair(forest, tree, projectPath);
    }

    /**
     * 注入运行时元数据。
     */
    public void injectRuntimeMetadata(ExecutableNode tree, List<Msg> prebuiltMsgs, List<ToolSpec> tools,
                                       String projectPath, UUID accountId, String agentName,
                                       List<String> convKbIds, UUID conversationId,
                                       RouteResult routeResult,
                                       List<String> convMcpServerIds) {
        injectMetadataRecursive(tree, prebuiltMsgs, tools, projectPath, accountId, agentName,
                convKbIds, conversationId, routeResult, convMcpServerIds);
    }

    /** 递归注入运行时元数据到树中所有节点。 */
    private void injectMetadataRecursive(ExecutableNode node, List<Msg> prebuiltMsgs, List<ToolSpec> tools,
                                          String projectPath, UUID accountId, String agentName,
                                          List<String> convKbIds, UUID conversationId,
                                          RouteResult routeResult,
                                          List<String> convMcpServerIds) {
        // ContentNode（TaskNode）和 InnerGoalNode 都支持 metadata
        java.util.Map<String, Object> meta;
        if (node instanceof ContentNode cn) {
            meta = cn.metadata();
        } else {
            // InnerGoalNode 等非 ContentNode 也支持 metadata 写入
            meta = node.metadata();
        }

        meta.put("prebuiltMessages", prebuiltMsgs);
        meta.put("_accountId", accountId.toString());
        if (projectPath != null) meta.put("_fileRootPath", projectPath);
        if (tools != null) meta.put("preferredToolSpecs", tools);
        if (convKbIds != null && !convKbIds.isEmpty()) {
            meta.put("_kbNames", String.join(",", convKbIds));
        }
        if (convMcpServerIds != null && !convMcpServerIds.isEmpty()) {
            meta.put("_mcpServerIds", String.join(",", convMcpServerIds));
        }

        // 路由元数据
        if (routeResult != null) {
            if (routeResult.agentName() != null) agentName = routeResult.agentName();
            if ("task".equals(routeResult.intent())) {
                meta.put("_routeTier", routeResult.tier());
                meta.put("_routeConfidence", routeResult.confidence());
                if (routeResult.matchedSkillIds() != null && !routeResult.matchedSkillIds().isEmpty()) {
                    meta.put("_matchedSkillIds", String.join(",", routeResult.matchedSkillIds()));
                }
            }
        }
        if (agentName != null && !agentName.isBlank()) meta.put("agentName", agentName);

        // 递归子节点
        for (var child : node.children()) {
            if (child instanceof ExecutableNode en) {
                injectMetadataRecursive(en, prebuiltMsgs, null, projectPath, accountId,
                        agentName, convKbIds, conversationId, routeResult, convMcpServerIds);
            }
        }
    }

    /**
     * 持久化森林 + 执行树。
     */
    public void persist(Forest forest, ExecutableNode tree, UUID accountId) {
        try {
            forestRepository.saveForest(forest, accountId);
            forestRepository.saveTree(tree, forest.forestId(), accountId);
        } catch (Exception e) {
            log.warn("持久化执行树失败（不影响执行）: forestId={} error={}", forest.forestId(), e.getMessage());
        }
    }

    /**
     * 将消息节点链接到执行树根节点。
     */
    public void linkMessage(UUID messageId, String treeRootNodeId) {
        if (messageId == null || treeRootNodeId == null) return;
        forestNodeJpaRepository.findById(messageId.toString()).ifPresent(entity -> {
            entity.setParentNodeId(treeRootNodeId);
            forestNodeJpaRepository.save(entity);
        });
    }

    /**
     * 更新根节点执行状态。
     */
    public void updateRootStatus(String treeRootNodeId, NodeStatus status, UUID accountId) {
        if (treeRootNodeId == null) return;
        try {
            forestRepository.updateNodeStatus(treeRootNodeId, status, accountId);
        } catch (Exception e) {
            log.warn("更新根节点状态失败: {}", e.getMessage());
        }
    }

    /** (forest, tree, projectPath) 三元组。 */
    public record ForestTreePair(Forest forest, TaskNode tree, String projectPath) {}
}
