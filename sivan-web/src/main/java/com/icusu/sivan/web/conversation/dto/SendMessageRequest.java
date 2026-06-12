package com.icusu.sivan.web.conversation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发送消息请求 DTO。
 */
@Data
public class SendMessageRequest {
    @Size(max = 100000, message = "消息内容过长")
    private String content;

    private String contentType = "text";
    /** 显式指定目标智能体（@agent 快捷指令），跳过路由决策 */
    private String targetAgent;
    private UUID replyToId;

    /** 图片文件 ID 或 base64 data URI 的列表 */
    private List<String> images;

    /** 音频文件 ID 或 base64 data URI 的列表 */
    private List<String> audios;

    /** 非图片文件附件列表：{fileId, fileName, mimeType, fileSize} */
    private List<Map<String, Object>> attachments;

    /** 选择的 LLM Provider ID */
    private UUID modelProviderId;

    /** 选择的 MCP 服务器 ID 列表 */
    private List<UUID> mcpServerIds;

    /** 流式输出（默认开启） */
    private boolean stream = true;

}
