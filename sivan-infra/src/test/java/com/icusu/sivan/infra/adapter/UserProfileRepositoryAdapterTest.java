package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
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
class UserProfileRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IUserProfileRepository repository;

    @Test
    void shouldSaveAndFindByAccountId() {
        UUID accountId = UUID.randomUUID();
        UserProfile profile = UserProfile.builder()
                .accountId(accountId)
                .name("测试用户")
                .bio("一个测试用户")
                .aiLanguage("中文")
                .expertise(List.of("Java", "Spring"))
                .active(true)
                .autoLearn(true)
                .build();
        profile = repository.save(profile);

        assertNotNull(profile.getProfileId());

        UserProfile found = repository.findByAccountId(accountId).orElse(null);
        assertNotNull(found);
        assertEquals("测试用户", found.getName());
        assertEquals(List.of("Java", "Spring"), found.getExpertise());
        assertTrue(found.isActive());
        assertTrue(found.isAutoLearn());
    }

    @Test
    void shouldUpdateExistingProfile() {
        UUID accountId = UUID.randomUUID();
        UserProfile profile = UserProfile.builder()
                .accountId(accountId).name("旧名").bio("旧简介")
                .expertise(List.of("Java")).active(true).build();
        profile = repository.save(profile);

        profile.setName("新名");
        profile.setBio("新简介");
        repository.save(profile);

        UserProfile found = repository.findByAccountId(accountId).orElse(null);
        assertNotNull(found);
        assertEquals("新名", found.getName());
        assertEquals("新简介", found.getBio());
    }

    @Test
    void findByAccountId_shouldReturnEmpty_whenNoProfile() {
        assertTrue(repository.findByAccountId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByAccountId_shouldNotReturnInactive() {
        UUID accountId = UUID.randomUUID();
        UserProfile profile = UserProfile.builder()
                .accountId(accountId).name("非活跃")
                .expertise(List.of("test")).active(false).build();
        repository.save(profile);

        assertTrue(repository.findByAccountId(accountId).isEmpty());
    }

    @Test
    void shouldDeleteProfile() {
        UUID accountId = UUID.randomUUID();
        UserProfile profile = UserProfile.builder()
                .accountId(accountId).name("待删除")
                .expertise(List.of("test")).active(true).build();
        profile = repository.save(profile);

        UUID profileId = profile.getProfileId();
        repository.delete(profileId);

        assertTrue(repository.findByAccountId(accountId).isEmpty());
    }
}
