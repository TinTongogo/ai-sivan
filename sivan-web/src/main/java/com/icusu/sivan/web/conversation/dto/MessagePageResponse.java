package com.icusu.sivan.web.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 消息分页响应，含 hasMore 标记避免前端重复请求。
 */
@Data
@AllArgsConstructor
public class MessagePageResponse {
    private List<MessageResponse> messages;
    private boolean hasMore;
}
