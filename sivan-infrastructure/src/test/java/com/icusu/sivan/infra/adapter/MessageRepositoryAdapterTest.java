package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class MessageRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IMessageRepository repository;

    @Test
    void shouldSaveAndFindByConversationId() {
        UUID convId = UUID.randomUUID();
        Message msg = Message.builder()
                .conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_USER)
                .content("测试消息")
                .build();
        repository.save(msg);
        assertNotNull(msg.getMessageId());

        List<Message> msgs = repository.findByConversationId(convId);
        assertEquals(1, msgs.size());
        assertEquals("测试消息", msgs.get(0).getContent());
    }

    @Test
    void shouldSaveMultipleMessagesWithOrder() {
        UUID convId = UUID.randomUUID();
        repository.save(Message.builder().conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_USER).content("第一条").build());
        repository.save(Message.builder().conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_ASSISTANT).content("第二条").build());

        List<Message> msgs = repository.findByConversationId(convId);
        assertEquals(2, msgs.size());
    }

    @Test
    void shouldDeleteByConversationId() {
        UUID convId = UUID.randomUUID();
        repository.save(Message.builder().conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_USER).content("待删除").build());
        repository.save(Message.builder().conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_ASSISTANT).content("也删除").build());

        repository.deleteByConversationId(convId);
        assertTrue(repository.findByConversationId(convId).isEmpty());
    }

    @Test
    void shouldDeleteSingleMessage() {
        UUID convId = UUID.randomUUID();
        Message msg = Message.builder().conversationId(convId).accountId(UUID.randomUUID())
                .role(Message.ROLE_USER).content("单条").build();
        repository.save(msg);

        repository.delete(msg.getMessageId());
        assertTrue(repository.findByConversationId(convId).isEmpty());
    }
}
