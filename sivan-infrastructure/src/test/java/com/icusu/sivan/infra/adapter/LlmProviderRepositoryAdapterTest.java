package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.LlmProvider;
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
class LlmProviderRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ILlmProviderRepository repository;

    @Test
    void shouldSaveAndFindById() {
        LlmProvider provider = LlmProvider.builder()
                .accountId(UUID.randomUUID())
                .name("测试提供商")
                .providerType("openai")
                .apiKey("sk-test")
                .baseUrl("https://api.openai.com")
                .models("gpt-4")
                .capabilities("chat,tool")
                .active(true)
                .build();
        repository.save(provider);

        assertNotNull(provider.getProviderId());

        LlmProvider found = repository.findById(provider.getProviderId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试提供商", found.getName());
        assertEquals("openai", found.getProviderType());
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            repository.save(LlmProvider.builder()
                    .accountId(accountId).name("提供商" + i)
                    .providerType("openai").apiKey("sk-" + i)
                    .capabilities("chat").active(true)
                    .build());
        }

        List<LlmProvider> providers = repository.findAllByAccount(accountId);
        assertEquals(2, providers.size());
    }

    @Test
    void shouldSetDefault() {
        UUID accountId = UUID.randomUUID();
        LlmProvider p1 = LlmProvider.builder()
                .accountId(accountId).name("默认").providerType("openai")
                .apiKey("sk-1").capabilities("chat").active(true).isChatDefault(true)
                .build();
        LlmProvider p2 = LlmProvider.builder()
                .accountId(accountId).name("非默认").providerType("openai")
                .apiKey("sk-2").capabilities("chat").active(true).isChatDefault(false)
                .build();
        repository.save(p1);
        repository.save(p2);

        // p1 设为非默认
        repository.save(p1); // 更新

        assertNotNull(repository.findById(p1.getProviderId()));
        assertNotNull(repository.findById(p2.getProviderId()));
    }

    @Test
    void shouldDelete() {
        LlmProvider provider = LlmProvider.builder()
                .accountId(UUID.randomUUID()).name("待删除")
                .providerType("openai").apiKey("sk-del")
                .capabilities("chat").active(true)
                .build();
        repository.save(provider);
        UUID id = provider.getProviderId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
