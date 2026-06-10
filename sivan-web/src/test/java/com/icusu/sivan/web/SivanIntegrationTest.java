package com.icusu.sivan.web;

import java.util.UUID;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.web.conversation.dto.CreateConversationRequest;
import com.icusu.sivan.web.conversation.dto.SendMessageRequest;
import com.icusu.sivan.web.conversation.dto.ConversationResponse;
import com.icusu.sivan.web.conversation.dto.MessageResponse;
import com.icusu.sivan.web.forest.service.ForestConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sivan 集成测试：端到端验证核心业务流。
 * 需 Docker PostgreSQL + pgvector + seed 数据。
 * 执行方法：mvn test -pl sivan-web -Dtest=SivanIntegrationTest -Dspring.profiles.active=test
 */
@Disabled("需运行中的 PostgreSQL + seed 数据（Docker: pgvector/pgvector:0.8.0-pg16）")
@SpringBootTest(classes = TestWebApplication.class)
@Tag("integration")
class SivanIntegrationTest {

    @Autowired
    private ForestConversationService conversationService;

    @Autowired
    private IConversationRepository conversationRepository;

    @Autowired
    private IMessageRepository messageRepository;

    private UUID accountId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        ConversationResponse conv = conversationService.create(accountId, new CreateConversationRequest());
        conversationId = conv.getConversationId();
    }

    @AfterEach
    void tearDown() {
        if (conversationId != null) {
            try { conversationService.delete(accountId, conversationId); } catch (Exception ignored) {}
        }
    }

    @Nested
    @DisplayName("集成场景 1: 发送消息 → 对话计数一致")
    class SendMsgAndVerifyCount {

        @Test
        @DisplayName("非流式发送后 messageCount 应与 DB 一致")
        void sendMessage_incrementsMessageCount() {
            SendMessageRequest req = new SendMessageRequest();
            req.setContent("测试消息");
            MessageResponse resp = conversationService.sendMessage(accountId, conversationId, req);

            assertNotNull(resp.getMessageId());
            assertEquals("user", resp.getRole());

            Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
            int dbCount = messageRepository.countByConversationId(conversationId);
            assertEquals(conv.getMessageCount(), dbCount);
            assertTrue(conv.getMessageCount() >= 1);
        }
    }

    @Nested
    @DisplayName("集成场景 2: 流式消息完成 → 消息状态 COMPLETED")
    class StreamMsgCompletes {

        @Test
        @DisplayName("流式消息返回 SSE 事件流且消息最终状态为 COMPLETED")
        void streamMessage_emitsEventsAndCompletes() throws Exception {
            SendMessageRequest req = new SendMessageRequest();
            req.setContent("请用一句话介绍 Spring Boot");

            Flux<String> flux = conversationService.streamMessage(accountId, conversationId, req);

            List<String> events = flux.take(Duration.ofSeconds(30)).collectList().block();
            assertNotNull(events);
            assertFalse(events.isEmpty());

            // 验证 meta 事件包含 messageId
            assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"meta\"") && e.contains("\"messageId\"")));

            // 等待后台流完成
            Thread.sleep(3000);

            // 验证至少有一条 assistant 消息
            List<Message> msgs = messageRepository.findByConversationId(conversationId);
            long assistantCount = msgs.stream().filter(m -> "assistant".equals(m.getRole())).count();
            assertTrue(assistantCount >= 1, "应有至少一条 assistant 消息");
        }
    }

    @Nested
    @DisplayName("集成场景 3: 并发对话 → 消息计数无丢失")
    class ConcurrentMsgCount {

        @Test
        @DisplayName("并发发送 10 条消息后 messageCount 应为 10")
        void concurrentMessages_messageCountCorrect() throws Exception {
            int numMessages = 10;
            CountDownLatch latch = new CountDownLatch(numMessages);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < numMessages; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        SendMessageRequest req = new SendMessageRequest();
                        req.setContent("并发消息 " + idx);
                        conversationService.sendMessage(accountId, conversationId, req);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(0, errors.get(), "并发消息应无错误");

            Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
            assertEquals(numMessages, messageRepository.countByConversationId(conversationId));
            assertEquals(numMessages, conv.getMessageCount());
        }
    }
}
