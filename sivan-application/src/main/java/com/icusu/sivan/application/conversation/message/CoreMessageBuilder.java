package com.icusu.sivan.application.conversation.message;

import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.file.FileStoragePort;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 将富化后的消息列表组装为核心 {@link Msg} 列表。
 * <p>
 * 消息按缓存稳定性排列（越稳定的越靠前），提升 vLLM RadixAttention 命中率：
 * <pre>
 * [0] System Prompt               → cache ① 跨对话
 * [1] Internal Tools              → cache ① 永远不变
 * [2] External Tools (MCP)        → cache ② 同对话
 * [3] 用户画像 + 项目上下文        → cache ② 同会话
 * [4] 上下文压缩摘要 + 记忆注入    → cache ③ 同轮
 * [5..] 对话历史消息              → 无缓存
 * </pre>
 */
public class CoreMessageBuilder {

    private final FileStoragePort fileStorageService;

    public CoreMessageBuilder(FileStoragePort fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 构建 LLM 消息列表（缓存优化版本）。
     *
     * @param systemPrompt      system prompt
     * @param internalTools     内部工具列表（file/bash 等），放在缓存层 ①
     * @param externalTools     外部工具列表（MCP Server 工具），放在缓存层 ②
     * @param contextMsgs       动态上下文消息（用户画像、flashback、项目提示等），追加在对话之后
     * @param compressedContext 上下文压缩摘要（含记忆注入），追加在对话之后
     * @param enriched          富化后的对话历史消息
     * @param excludeMessageId  需要排除的消息 ID
     * @param accountId         当前用户 ID
     * @return 缓存优化排列的 Msg 列表（sys→tools→history→context→question）
     */
    public List<Msg> build(String systemPrompt,
                           List<ToolSpec> internalTools,
                           List<ToolSpec> externalTools,
                           List<Msg> contextMsgs,
                           String compressedContext,
                           List<EnrichedMessage> enriched,
                           UUID excludeMessageId,
                           UUID accountId) {
        List<Msg> msgs = new ArrayList<>();

        // 缓存层 ①：永不变 — 全账号共享
        msgs.add(Msg.of(Role.SYSTEM, systemPrompt));
        if (internalTools != null && !internalTools.isEmpty()) {
            msgs.add(Msg.of(Role.SYSTEM, buildToolsSchema("内部工具", internalTools)));
        }

        // 缓存层 ②：同配置共享
        if (externalTools != null && !externalTools.isEmpty()) {
            msgs.add(Msg.of(Role.SYSTEM, buildToolsSchema("外部工具", externalTools)));
        }

        // 缓存层 ③：对话历史 — 同对话共享前缀不断增长
        for (EnrichedMessage em : enriched) {
            com.icusu.sivan.domain.conversation.Message original = em.getOriginal();
            if (excludeMessageId != null && excludeMessageId.equals(original.getMessageId())) continue;

            Role role = original.isUser() ? Role.USER : Role.ASSISTANT;
            String content = em.getEnrichedContent();

            if (!em.isImagesAttached() && !em.isAudiosAttached()) {
                msgs.add(Msg.of(role, content));
            } else {
                var builder = Msg.builder().role(role).text(content);
                if (em.isImagesAttached()) {
                    for (String imageRef : em.getImageRefs()) {
                        builder.add(new Content.Image(resolveMimeType(imageRef), resolveBytes(imageRef, accountId)));
                    }
                }
                if (em.isAudiosAttached()) {
                    for (String audioRef : em.getAudioRefs()) {
                        builder.add(new Content.Audio(resolveMimeType(audioRef), resolveBytes(audioRef, accountId), null));
                    }
                }
                msgs.add(builder.build());
            }
        }

        // 动态上下文（用户画像、flashback、项目提示等）放在对话之后，不影响 cache prefix
        if (contextMsgs != null) {
            msgs.addAll(contextMsgs);
        }
        if (compressedContext != null && !compressedContext.isBlank()) {
            msgs.add(Msg.of(Role.USER,
                    com.icusu.sivan.agent.prompt.ChatPrompts.contextInjection(compressedContext).content()));
        }

        return msgs;
    }

    /**
     * 构建 LLM 消息列表（向后兼容，无缓存优化）。
     */
    public List<Msg> build(String systemPrompt,
                           List<EnrichedMessage> enriched,
                           UUID excludeMessageId,
                           UUID accountId) {
        return build(systemPrompt, null, null, null, null, enriched, excludeMessageId, accountId);
    }

    /** 将工具列表转为 tools schema markdown 文本。 */
    private static String buildToolsSchema(String label, List<ToolSpec> tools) {
        StringBuilder sb = new StringBuilder("## ").append(label).append("\n\n");
        for (ToolSpec t : tools) {
            sb.append("- **").append(t.name()).append("**: ").append(t.description()).append("\n");
        }
        return sb.toString();
    }

    private byte[] resolveBytes(String ref, UUID accountId) {
        if (ref == null) throw new IllegalArgumentException("ref 不能为空");
        if (ref.startsWith("data:")) {
            int comma = ref.indexOf(',');
            String base64Data = comma > 0 ? ref.substring(comma + 1) : ref;
            return Base64.getDecoder().decode(base64Data);
        }
        try {
            UUID.fromString(ref);
            String dataUri = fileStorageService.resolveToBase64(accountId, UUID.fromString(ref));
            int comma = dataUri.indexOf(',');
            String base64Data = comma > 0 ? dataUri.substring(comma + 1) : dataUri;
            return Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的引用: " + ref, e);
        }
    }

    private static String resolveMimeType(String ref) {
        if (ref != null && ref.startsWith("data:")) {
            String rest = ref.substring("data:".length());
            int semicolon = rest.indexOf(';');
            if (semicolon > 0) return rest.substring(0, semicolon);
        }
        return "image/png";
    }

}
