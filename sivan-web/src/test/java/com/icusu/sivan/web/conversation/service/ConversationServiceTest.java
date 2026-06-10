package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.domain.conversation.*;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.forest.service.ForestConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.Mockito;

/**
 * 对话服务测试 — 验证委托链路正确性。
 * <p>
 * 注意：Mockito 在 Java 26 下对部分字节码存在兼容问题，
 * 因此复杂子服务以匿名子类方式创建，避免使用 mock()。
 */
class ConversationServiceTest {

    private ForestConversationService conversationService;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var conversationRepository = Mockito.mock(IConversationRepository.class);
        var messageRepository = Mockito.mock(IMessageRepository.class);

        // 使用匿名子类避免 Mockito inline + Java 26 兼容问题
        var conversationCrudService = new ConversationCrudService(
                conversationRepository, null, messageRepository) {
            @Override public ConversationResponse create(UUID a, CreateConversationRequest r) {
                return ConversationResponse.builder().title(r.getTitle()).build();
            }
            @Override public ConversationResponse getById(UUID a, UUID cid) {
                return ConversationResponse.builder().conversationId(cid).title("对话").build();
            }
            @Override public List<ConversationResponse> list(UUID a, UUID p) { return List.of(); }
            @Override public ConversationResponse update(UUID a, UUID cid, UpdateConversationRequest r) {
                return ConversationResponse.builder().conversationId(cid).title(r.getTitle()).build();
            }
            @Override public void delete(UUID a, UUID cid) {}
            @Override public Conversation findOwned(UUID a, UUID cid) {
                return Conversation.builder().conversationId(cid).accountId(a).build();
            }
        };

        var messageCrudService = new MessageCrudService(messageRepository, null, null) {
            @Override public MessageResponse sendMessage(UUID a, UUID cid, SendMessageRequest r) {
                return MessageResponse.builder().content(r.getContent()).build();
            }
            @Override public MessagePageResponse getMessages(UUID a, UUID cid, Integer b, int l) {
                return new MessagePageResponse(List.of(), false);
            }
            @Override public MessagePageResponse getMessages(UUID a, UUID cid) {
                return new MessagePageResponse(List.of(), false);
            }
            @Override public void deleteMessage(UUID a, UUID mid) {}
            @Override public int countMessages(UUID a, UUID cid) { return 5; }
            @Override public MessageResponse rateMessage(UUID a, UUID mid, String r) {
                return MessageResponse.builder().rating(r).build();
            }
            @Override public Message createAssistantMessage(Conversation c, UUID a) {
                return Message.builder().conversationId(c.getConversationId()).accountId(a)
                        .role("assistant").content("").status(MessageStatus.RUNNING).build();
            }
        };

        conversationService = new ForestConversationService(conversationRepository, messageRepository,
                new com.icusu.sivan.infra.shared.sse.StreamManager(),
                null, null, null, null,
                conversationCrudService, messageCrudService, null, null, null, null, null, null);
    }

    // ============ 对话 CRUD 委托 ============

    @Test
    void create_shouldDelegate() {
        var request = new CreateConversationRequest();
        request.setTitle("测试对话");
        var response = conversationService.create(accountId, request);
        assertEquals("测试对话", response.getTitle());
    }

    @Test
    void getById_shouldDelegate() {
        var response = conversationService.getById(accountId, UUID.randomUUID());
        assertEquals("对话", response.getTitle());
    }

    @Test
    void list_shouldDelegate() {
        assertTrue(conversationService.list(accountId, null).isEmpty());
    }

    @Test
    void update_shouldDelegate() {
        var request = new UpdateConversationRequest();
        request.setTitle("新标题");
        var response = conversationService.update(accountId, UUID.randomUUID(), request);
        assertEquals("新标题", response.getTitle());
    }

    @Test
    void delete_shouldDelegate() {
        conversationService.delete(accountId, UUID.randomUUID());
    }

    // ============ 消息 CRUD 委托 ============

    @Test
    void sendMessage_shouldDelegate() {
        var request = new SendMessageRequest();
        request.setContent("你好");
        var response = conversationService.sendMessage(accountId, UUID.randomUUID(), request);
        assertEquals("你好", response.getContent());
    }

    @Test
    void getMessages_shouldDelegate() {
        var page = conversationService.getMessages(accountId, UUID.randomUUID());
        assertFalse(page.isHasMore());
    }

    @Test
    void deleteMessage_shouldDelegate() {
        conversationService.deleteMessage(accountId, UUID.randomUUID());
    }

    @Test
    void countMessages_shouldDelegate() {
        assertEquals(5, conversationService.countMessages(accountId, UUID.randomUUID()));
    }

    @Test
    void rateMessage_shouldDelegate() {
        var response = conversationService.rateMessage(accountId, UUID.randomUUID(), "like");
        assertEquals("like", response.getRating());
    }
}
