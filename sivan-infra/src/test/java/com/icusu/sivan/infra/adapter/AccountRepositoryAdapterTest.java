package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class AccountRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IAccountRepository accountRepository;

    @Test
    void shouldSaveAndFindById() {
        Account account = Account.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hash123")
                .displayName("测试用户")
                .status("active")
                .build();
        accountRepository.save(account);

        assertNotNull(account.getAccountId());

        Account found = accountRepository.findById(account.getAccountId()).orElse(null);
        assertNotNull(found);
        assertEquals("testuser", found.getUsername());
        assertEquals("test@example.com", found.getEmail());
        assertEquals("active", found.getStatus());
    }

    @Test
    void shouldFindByUsername() {
        Account account = Account.builder()
                .username("uniqueuser")
                .email("unique@example.com")
                .passwordHash("hash")
                .displayName("唯一用户")
                .build();
        accountRepository.save(account);

        Account found = accountRepository.findByUsername("uniqueuser").orElse(null);
        assertNotNull(found);
        assertEquals("uniqueuser", found.getUsername());
    }

    @Test
    void shouldFindByEmail() {
        Account account = Account.builder()
                .username("emailtest")
                .email("findme@example.com")
                .passwordHash("hash")
                .build();
        accountRepository.save(account);

        Account found = accountRepository.findByEmail("findme@example.com").orElse(null);
        assertNotNull(found);
    }

    @Test
    void existsByUsername_shouldReturnTrue_whenExists() {
        accountRepository.save(Account.builder()
                .username("existsuser").email("e@e.com").passwordHash("h").build());

        assertTrue(accountRepository.existsByUsername("existsuser"));
        assertFalse(accountRepository.existsByUsername("nonexistent"));
    }

    @Test
    void existsByEmail_shouldReturnTrue_whenExists() {
        accountRepository.save(Account.builder()
                .username("emailcheck").email("check@e.com").passwordHash("h").build());

        assertTrue(accountRepository.existsByEmail("check@e.com"));
        assertFalse(accountRepository.existsByEmail("missing@e.com"));
    }

    @Test
    void existsByShortId_shouldReturnTrue_whenExists() {
        accountRepository.save(Account.builder()
                .username("shortidtest").email("s@e.com").passwordHash("h")
                .shortId("swift-dawn").build());

        assertTrue(accountRepository.existsByShortId("swift-dawn"));
        assertFalse(accountRepository.existsByShortId("nonexistent-id"));
    }

    @Test
    void shouldSaveAndRetainAllFields() {
        Account account = Account.builder()
                .username("fullfields")
                .email("full@example.com")
                .passwordHash("complex-hash-123")
                .displayName("完整字段用户")
                .shortId("bright-moon")
                .status("disabled")
                .preferences("{\"theme\":\"dark\"}")
                .quota("1000")
                .build();
        accountRepository.save(account);

        Account found = accountRepository.findById(account.getAccountId()).orElse(null);
        assertNotNull(found);
        assertEquals("fullfields", found.getUsername());
        assertEquals("complex-hash-123", found.getPasswordHash());
        assertEquals("bright-moon", found.getShortId());
        assertEquals("disabled", found.getStatus());
        assertEquals("{\"theme\":\"dark\"}", found.getPreferences());
        assertEquals("1000", found.getQuota());
    }
}
