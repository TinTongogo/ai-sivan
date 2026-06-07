package com.icusu.sivan.web.conversation.service.compress;

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
        compressor = new ConversationCompressor(messageRepository);
    }

    // ── selectLevel ──

    @Test
    void selectLevel_预算充足返回PASSTHROUGH() {
        assertEquals(ConversationCompressor.Level.PASSTHROUGH, compressor.selectLevel(100, 150));
    }

    @Test
    void selectLevel_预算相等返回PASSTHROUGH() {
        assertEquals(ConversationCompressor.Level.PASSTHROUGH, compressor.selectLevel(100, 100));
    }

    @Test
    void selectLevel_比值零点六返回LIGHT() {
        assertEquals(ConversationCompressor.Level.LIGHT, compressor.selectLevel(100, 60));
    }

    @Test
    void selectLevel_比值零点三返回MODERATE() {
        assertEquals(ConversationCompressor.Level.MODERATE, compressor.selectLevel(100, 30));
    }

    @Test
    void selectLevel_比值低于零点三返回AGGRESSIVE() {
        assertEquals(ConversationCompressor.Level.AGGRESSIVE, compressor.selectLevel(100, 20));
    }

    @Test
    void selectLevel_totalTokens为零默认PASSTHROUGH() {
        assertEquals(ConversationCompressor.Level.PASSTHROUGH, compressor.selectLevel(0, 10));
    }

    // ── isFiller ──

    @Test
    void isFiller_null返回true() {
        assertTrue(compressor.isFiller(null));
    }

    @Test
    void isFiller_空白返回true() {
        assertTrue(compressor.isFiller("   "));
    }

    @Test
    void isFiller_中文填充语() {
        assertTrue(compressor.isFiller("好的"));
        assertTrue(compressor.isFiller("明白了"));
        assertTrue(compressor.isFiller("嗯嗯"));
    }

    @Test
    void isFiller_英文填充语() {
        assertTrue(compressor.isFiller("ok"));
    }

    @Test
    void isFiller_正常文本返回false() {
        assertFalse(compressor.isFiller("今天天气不错，我们继续讨论需求。"));
    }

    @Test
    void isFiller_长文本返回false() {
        assertFalse(compressor.isFiller("好的，我们开始讨论"));
    }

    // ── scoreMessage ──

    @Test
    void scoreMessage_用户消息权重高() {
        Message userMsg = createMsg("hello", true);
        Message asstMsg = createMsg("response", false);
        double userScore = compressor.scoreMessage(userMsg, 5, 10, null);
        double asstScore = compressor.scoreMessage(asstMsg, 5, 10, null);
        assertTrue(userScore > asstScore);
    }

    @Test
    void scoreMessage_首条消息加分() {
        Message msg = createMsg("start", false);
        double firstScore = compressor.scoreMessage(msg, 0, 5, null);
        double midScore = compressor.scoreMessage(msg, 2, 5, null);
        assertTrue(firstScore > midScore);
    }

    @Test
    void scoreMessage_含代码块加分() {
        Message code = createMsg("代码：```\nprint('hello')\n```", true);
        Message plain = createMsg("普通文本", true);
        double codeScore = compressor.scoreMessage(code, 1, 3, null);
        double plainScore = compressor.scoreMessage(plain, 1, 3, null);
        assertTrue(codeScore > plainScore);
    }

    @Test
    void scoreMessage_查询关键词加分() {
        Message msg = createMsg("Python 代码优化", true);
        double withQuery = compressor.scoreMessage(msg, 1, 3, "Python 性能");
        double withoutQuery = compressor.scoreMessage(msg, 1, 3, null);
        assertTrue(withQuery > withoutQuery);
    }

    @Test
    void scoreMessage_填充语减分() {
        Message filler = createMsg("好的", true);
        Message normal = createMsg("这里是详细的需求说明", true);
        double fs = compressor.scoreMessage(filler, 1, 3, null);
        double ns = compressor.scoreMessage(normal, 1, 3, null);
        assertTrue(ns > fs);
    }

    // ── findAnchors ──

    @Test
    void findAnchors_首条始终为锚点() {
        List<Message> msgs = List.of(createMsg("a", true), createMsg("b", false));
        var anchors = compressor.findAnchors(msgs, null);
        assertTrue(anchors.contains(0));
    }

    @Test
    void findAnchors_任务目标锚点() {
        UUID goalId = UUID.randomUUID();
        Message goal = createMsg("goal", true);
        Message other = createMsg("other", true);
        when(goal.getMessageId()).thenReturn(goalId);
        when(other.getMessageId()).thenReturn(UUID.randomUUID());

        var anchors = compressor.findAnchors(List.of(goal, other), goalId);
        assertTrue(anchors.contains(0));
        assertTrue(anchors.contains(1)); // last user
    }

    @Test
    void findAnchors_代码块锚点() {
        Message code = createMsg("```code```", true);
        Message plain = createMsg("plain", false);
        var anchors = compressor.findAnchors(List.of(code, plain), null);
        assertTrue(anchors.contains(0));
    }

    // ── extractSentences ──

    @Test
    void extractSentences_短消息完整保留() {
        Message msg = createMsg("简短消息", true);
        String result = compressor.extractSentences(msg, 500);
        assertTrue(result.contains("用户"));
        assertTrue(result.contains("简短消息"));
    }

    @Test
    void extractSentences_null内容返回空() {
        Message msg = createMsg(null, true);
        assertEquals("", compressor.extractSentences(msg, 100));
    }

    @Test
    void extractSentences_多句提取首尾() {
        Message msg = createMsg("第一句。第二句。第三句。第四句。第五句。", true);
        String result = compressor.extractSentences(msg, 20);
        assertTrue(result.contains("第一句"));
        assertTrue(result.contains("第五句"));
    }

    @Test
    void extractSentences_超长仅保留首句() {
        Message msg = createMsg("第一句。第二句。第三句。第四句。第五句。第六句。第七句。第八句。", true);
        String result = compressor.extractSentences(msg, 8);
        assertTrue(result.contains("第一句") || result.isEmpty());
    }

    // ── formatMessage ──

    @Test
    void formatMessage_用户消息格式() {
        Message msg = createMsg("测试", true);
        String result = compressor.formatMessage(msg);
        assertEquals("用户: 测试\n", result);
    }

    @Test
    void formatMessage_助手消息格式() {
        Message msg = createMsg("回答", false);
        String result = compressor.formatMessage(msg);
        assertEquals("助手: 回答\n\n", result);
    }

    @Test
    void formatMessage_null内容() {
        Message msg = createMsg(null, true);
        String result = compressor.formatMessage(msg);
        assertEquals("用户: \n", result);
    }

    // ── estimateToken ──

    @Test
    void estimateTokens_null返回0() {
        assertEquals(0, compressor.estimateTokens(null));
    }

    @Test
    void estimateTokens_英文按空格分词() {
        int tokens = compressor.estimateTokens("hello world foo bar");
        assertTrue(tokens > 0);
    }

    @Test
    void estimateTokens_中文按字符计数() {
        int tokens = compressor.estimateTokens("中文测试");
        assertTrue(tokens > 0);
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
