package com.icusu.sivan.infra.conversation.adapter;

import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.IConversationRepository;
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
class ConversationRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IConversationRepository repository;

    @Test
    void shouldSaveAndFindById() {
        Conversation conv = Conversation.builder()
                .accountId(UUID.randomUUID())
                .title("测试对话")
                .build();
        repository.save(conv);

        assertNotNull(conv.getConversationId());

        Conversation found = repository.findById(conv.getConversationId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试对话", found.getTitle());
        assertEquals(conv.getAccountId(), found.getAccountId());
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.save(Conversation.builder()
                    .accountId(accountId).title("对话" + i).build());
        }

        List<Conversation> convs = repository.findAllByAccount(accountId);
        assertEquals(3, convs.size());
    }

    @Test
    void shouldFindAllByAccountAndProject() {
        UUID accountId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        repository.save(Conversation.builder()
                .accountId(accountId).projectId(projectId).title("项目对话").build());

        List<Conversation> convs = repository.findAllByAccountAndProject(accountId, projectId);
        assertEquals(1, convs.size());
    }

    @Test
    void shouldUpdate() {
        Conversation conv = Conversation.builder()
                .accountId(UUID.randomUUID()).title("旧标题").build();
        repository.save(conv);

        conv.setTitle("新标题");
        repository.update(conv);

        Conversation found = repository.findById(conv.getConversationId()).orElse(null);
        assertNotNull(found);
        assertEquals("新标题", found.getTitle());
    }

    @Test
    void shouldDelete() {
        Conversation conv = Conversation.builder()
                .accountId(UUID.randomUUID()).title("待删除").build();
        repository.save(conv);
        UUID id = conv.getConversationId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
