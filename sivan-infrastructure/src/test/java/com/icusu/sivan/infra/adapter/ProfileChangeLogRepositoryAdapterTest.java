package com.icusu.sivan.infra.account.adapter;

import com.icusu.sivan.domain.account.IProfileChangeLogRepository;
import com.icusu.sivan.domain.account.ProfileChangeLog;
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
class ProfileChangeLogRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IProfileChangeLogRepository repository;

    @Test
    void shouldSaveAndFindByAccountId() {
        UUID accountId = UUID.randomUUID();
        ProfileChangeLog log = ProfileChangeLog.of(accountId, "manual", "name", "旧名", "新名");
        repository.save(log);
        assertNotNull(log.getLogId());

        List<ProfileChangeLog> logs = repository.findByAccountId(accountId, 10);
        assertEquals(1, logs.size());
        assertEquals("name", logs.get(0).getFieldName());
        assertEquals("新名", logs.get(0).getNewValue());
    }

    @Test
    void shouldFindMultipleLogs() {
        UUID accountId = UUID.randomUUID();
        repository.save(ProfileChangeLog.of(accountId, "manual", "name", "旧", "新"));
        repository.save(ProfileChangeLog.of(accountId, "auto_learn", "bio", "", "新简介"));

        List<ProfileChangeLog> logs = repository.findByAccountId(accountId, 10);
        assertEquals(2, logs.size());
    }
}
