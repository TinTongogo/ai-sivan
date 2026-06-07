package com.icusu.sivan.infra.token.adapter;

import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.domain.token.TokenUsage;
import com.icusu.sivan.domain.token.TokenUsageRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class TokenUsageRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private TokenUsageRepository repository;

    @Test
    void shouldSaveTokenUsage() {
        UUID id = repository.save(TokenUsage.builder()
                .accountId(UUID.randomUUID())
                .modelName("gpt-4o")
                .source(TokenSource.CHAT)
                .inputTokens(100)
                .outputTokens(50)
                .build());

        assertNotNull(id);
    }
}
