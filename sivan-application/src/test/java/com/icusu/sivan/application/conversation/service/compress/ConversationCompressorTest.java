package com.icusu.sivan.application.conversation.service.compress;

import com.icusu.sivan.application.conversation.compress.ConversationCompressor;
import com.icusu.sivan.domain.conversation.MessageImportanceScorer;
import com.icusu.sivan.domain.conversation.CompressResult;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationCompressorTest {

    @Mock private IMessageRepository messageRepository;
    private ConversationCompressor compressor;

    @BeforeEach
    void setUp() {
        compressor = new ConversationCompressor(messageRepository, new MessageImportanceScorer());
    }

    // ── compress ──

    @Test
    void compress_无消息返回空结果() {
        when(messageRepository.findByConversationId(any())).thenReturn(List.of());
        CompressResult result = compressor.compress(UUID.randomUUID(), 100, "query").block();
        assertNotNull(result);
        assertTrue(result.isFullText());
        assertTrue(result.getSummary().isEmpty());
    }

    @Test
    void compress_只有用户和助手消息() {
        List<Message> msgs = List.of(
                createMsg("我需要帮助", true),
                createMsg("好的，请说明", false));
        when(messageRepository.findByConversationId(any())).thenReturn(msgs);

        CompressResult result = compressor.compress(UUID.randomUUID(), 500, "帮助").block();
        assertNotNull(result);
        assertTrue(result.isFullText());
        assertTrue(result.getSummary().contains("用户"));
    }

    @Test
    void compress_预算吃紧走LIGHT() {
        List<Message> msgs = List.of(
                createMsg("第一轮问题", true),
                createMsg("回答内容", false),
                createMsg("好的", true),
                createMsg("第二轮讨论", true));
        when(messageRepository.findByConversationId(any())).thenReturn(msgs);

        CompressResult result = compressor.compress(UUID.randomUUID(), 5, "讨论").block();
        assertNotNull(result);
        // 预算低 → not passthrough
        assertFalse(result.isFullText());
    }

    // ── 辅助 ──

    private static Message createMsg(String content, boolean isUser) {
        Message msg = mock(Message.class);
        when(msg.getContent()).thenReturn(content);
        when(msg.isUser()).thenReturn(isUser);
        when(msg.isAssistant()).thenReturn(!isUser);
        when(msg.getMessageId()).thenReturn(UUID.randomUUID());
        return msg;
    }
}
