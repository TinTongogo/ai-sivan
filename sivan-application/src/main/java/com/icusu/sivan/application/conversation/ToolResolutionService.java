package com.icusu.sivan.application.conversation;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.application.conversation.dto.SendMessageRequest;
import com.icusu.sivan.application.service.GroupService;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.file.FileStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具解析服务 — 解析聊天工具、MCP 服务器、内部工具，处理附件复制。
 * <p>
 * 从 {@link PromptContextService} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolResolutionService {

    private final ToolRegistryImpl toolRegistry;
    private final ToolResolver toolAutoResolver;
    private final ToolEnricher toolEnricher;
    private final McpConnectionManager mcpConnectionManager;
    private final IConversationRepository conversationRepository;
    private final FileStoragePort fileStorageService;
    private final GroupService groupService;
    private final ContextAssemblyService contextAssemblyService;

    /** 工具解析结果。 */
    public record ChatToolResult(List<ToolSpec> tools, List<Msg> contextMsgs) {}

    /**
     * 解析聊天工具列表（内部工具 + MCP 工具）。
     */
    public ChatToolResult resolveChatTools(Conversation conversation, String userContent,
                                           String toolConvContext, UUID accountId) {

        String userProfileSection = contextAssemblyService.buildUserProfileSection(accountId, userContent);
        String flashbackSection = contextAssemblyService.buildFlashbackSection(accountId, userContent);
        String fileSnapshot = contextAssemblyService.buildFileSnapshot(accountId, conversation.getProjectId());
        String projectHint = contextAssemblyService.buildProjectHint(conversation, accountId);

        List<Msg> contextMsgs = buildDynamicContextMsgs(userProfileSection, flashbackSection, fileSnapshot, projectHint);
        List<ToolSpec> internalTools = getInternalTools();

        log.info("CHAT 工具解析: internalTools={} mcpServers={}",
                internalTools.stream().map(ToolSpec::name).toList(), conversation.getMcpServerIds());

        List<String> enabledServers = conversation.getMcpServerIds();
        if (enabledServers != null && !enabledServers.isEmpty()) {
            List<ToolSpec> mcpTools = resolveMcpTools(conversation);
            if (mcpTools != null) {
                List<ToolSpec> merged = new ArrayList<>(mcpTools);
                merged.addAll(internalTools);
                log.info("CHAT 工具解析: 合并模式 MCP={} 内部={}", mcpTools.size(), internalTools.size());
                return new ChatToolResult(merged, contextMsgs);
            }
            try {
                MatchedTools matched = toolAutoResolver.resolveForChat(userContent, toolConvContext, accountId);
                if (!matched.isEmpty()) {
                    var semanticTools = new ArrayList<>(toolEnricher.toSchemas(matched));
                    semanticTools.addAll(internalTools);
                    String toolText = toolEnricher.enrichPrompt("", matched.metas());
                    log.info("CHAT 工具解析: 语义降级模式 tools={}",
                            semanticTools.stream().map(ToolSpec::name).toList());
                    if (!toolText.isEmpty()) {
                        contextMsgs = appendToolTextToContextMsgs(contextMsgs, toolText);
                    }
                    return new ChatToolResult(semanticTools, contextMsgs);
                }
            } catch (Exception e) {
                log.warn("CHAT 工具语义匹配降级失败: {}", e.getMessage());
            }
        }
        if (internalTools.isEmpty()) {
            log.info("CHAT 工具解析: 无可用工具");
            return new ChatToolResult(null, contextMsgs);
        }
        log.info("CHAT 工具解析: 内部工具模式 tools={}", internalTools.stream().map(ToolSpec::name).toList());
        return new ChatToolResult(internalTools, contextMsgs);
    }

    public List<ToolSpec> getInternalTools() {
        Set<String> allowed = Set.of("bash", "file_read", "file_write", "file_list", "file_search", "file_delete");
        return toolRegistry.allSpecs().stream()
                .filter(s -> allowed.contains(s.name()))
                .sorted(java.util.Comparator.comparing(ToolSpec::name))
                .toList();
    }

    public List<ToolSpec> resolveMcpTools(Conversation conversation) {
        List<String> enabledServers = conversation.getMcpServerIds();
        if (enabledServers == null || enabledServers.isEmpty()) return null;
        boolean anyConnected = false;
        List<String> validServers = new ArrayList<>();
        for (String serverId : enabledServers) {
            try {
                UUID sid = UUID.fromString(serverId);
                if (mcpConnectionManager.isConnected(sid)) {
                    validServers.add(serverId);
                    anyConnected = true;
                } else {
                    mcpConnectionManager.connectSync(sid);
                    if (mcpConnectionManager.isConnected(sid)) {
                        validServers.add(serverId);
                        anyConnected = true;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("MCP 服务器已被删除，从对话中移除: serverId={}", serverId);
            }
        }
        if (validServers.size() < enabledServers.size()) {
            conversation.setMcpServerIds(validServers.isEmpty() ? null : validServers);
            conversationRepository.update(conversation);
        }
        if (!anyConnected) return null;
        return toolRegistry.allSpecs().stream()
                .map(s -> new ToolSpec(s.name(), s.description(), s.inputSchema()))
                .collect(Collectors.toList());
    }

    // ====== 动态上下文消息 ======

    public static List<Msg> buildDynamicContextMsgs(String userProfileSection, String flashbackSection,
                                                     String fileSnapshot, String projectHint) {
        StringBuilder ctxSb = new StringBuilder();
        if (userProfileSection != null) ctxSb.append("\n").append(userProfileSection);
        if (flashbackSection != null) ctxSb.append("\n\n").append(flashbackSection);
        if (fileSnapshot != null) ctxSb.append("\n\n").append(fileSnapshot);
        if (projectHint != null) ctxSb.append("\n\n").append(projectHint);

        if (ctxSb.isEmpty()) return List.of();
        return List.of(Msg.of(Role.USER, ctxSb.toString().strip()));
    }

    public static List<Msg> appendToolTextToContextMsgs(List<Msg> contextMsgs, String toolText) {
        if (toolText == null || toolText.isBlank()) return contextMsgs;
        List<Msg> result = new ArrayList<>(contextMsgs);
        int lastIdx = result.size() - 1;
        if (lastIdx >= 0) {
            Msg last = result.get(lastIdx);
            String combined = last.text() + "\n\n" + toolText;
            result.set(lastIdx, Msg.of(Role.USER, combined));
        } else {
            result.add(Msg.of(Role.USER, toolText));
        }
        return result;
    }

    // ====== 附件复制到沙盒 ======

    public List<String> copyAttachmentsToSandbox(UUID accountId, Conversation conversation,
                                                  SendMessageRequest request) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) return List.of();

        List<Map<String, Object>> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) return List.of();

        String sandboxPath = groupService.getProjectRootPath(accountId, projectId);
        if (sandboxPath == null) return List.of();

        Path uploadDir = Paths.get(sandboxPath);
        List<String> failedFiles = new ArrayList<>();

        for (Map<String, Object> att : attachments) {
            String fileIdStr = att.get("fileId") != null ? att.get("fileId").toString() : null;
            String fileName = (String) att.get("fileName");
            if (fileIdStr == null || fileName == null || fileName.isBlank()) continue;

            String safeName = sanitizeFileName(fileName);
            if (safeName == null) {
                log.warn("跳过不安全的文件名: {}", fileName);
                failedFiles.add(fileName);
                continue;
            }

            try {
                byte[] bytes = fileStorageService.loadBytes(accountId, UUID.fromString(fileIdStr));
                if (bytes == null) continue;
                Files.createDirectories(uploadDir);
                Files.write(uploadDir.resolve(safeName), bytes);
                log.info("上传文件已复制到沙盒工作目录: {} ({} bytes)", safeName, bytes.length);
            } catch (Exception e) {
                log.warn("复制上传文件到沙盒失败: fileId={}, name={}", fileIdStr, fileName, e);
                failedFiles.add(fileName);
            }
        }

        return failedFiles;
    }

    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String safe = Path.of(fileName).getFileName().toString();
        if (safe.isEmpty() || safe.equals("..") || safe.startsWith(".")) return null;
        return safe;
    }
}
