package com.icusu.sivan.infra.memory.adapter;

import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共享模板仓储适配器集成测试。
 */
@Sql("/disable-fk.sql")
@Transactional
class SharedTemplateRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ISharedTemplateRepository repository;

    private final UUID ownerId = UUID.randomUUID();

    /** 保存后能通过 ID 查询到。 */
    @Test
    void shouldSaveAndFindById() {
        SharedTemplate template = createTemplate(SharedTemplate.Visibility.PUBLIC);
        repository.save(template);

        assertNotNull(template.getTemplateId());

        SharedTemplate found = repository.findById(template.getTemplateId()).orElse(null);
        assertNotNull(found);
        assertEquals(SharedTemplate.Visibility.PUBLIC, found.getVisibility());
        assertEquals(ownerId, found.getOwnerAccountId());
    }

    /** 按可见性过滤正确返回。 */
    @Test
    void shouldFindByVisibility() {
        repository.save(createTemplate(SharedTemplate.Visibility.PUBLIC));
        repository.save(createTemplate(SharedTemplate.Visibility.TENANT));

        List<SharedTemplate> publicTemplates = repository.findByVisibility(SharedTemplate.Visibility.PUBLIC);
        assertFalse(publicTemplates.isEmpty());
        assertTrue(publicTemplates.stream().allMatch(t -> t.getVisibility() == SharedTemplate.Visibility.PUBLIC));
    }

    /** 排除 owner 的可见性查询。 */
    @Test
    void shouldFindByVisibilityExcludingOwner() {
        UUID otherOwner = UUID.randomUUID();
        SharedTemplate owned = createTemplate(SharedTemplate.Visibility.PUBLIC);
        owned.setOwnerAccountId(otherOwner);
        repository.save(owned);
        repository.save(createTemplate(SharedTemplate.Visibility.PUBLIC));

        List<SharedTemplate> notMine = repository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, otherOwner);
        assertFalse(notMine.isEmpty());
        assertTrue(notMine.stream().noneMatch(t -> otherOwner.equals(t.getOwnerAccountId())));
    }

    /** 按 owner 查询。 */
    @Test
    void shouldFindByOwner() {
        repository.save(createTemplate(SharedTemplate.Visibility.PUBLIC));
        repository.save(createTemplate(SharedTemplate.Visibility.LIST));

        List<SharedTemplate> owned = repository.findByOwner(ownerId);
        assertEquals(2, owned.size());
    }

    /** 标记为孤儿不物理删除。 */
    @Test
    void shouldMarkOrphanedNotDelete() {
        repository.save(createTemplate(SharedTemplate.Visibility.PUBLIC));
        repository.markOrphanedByOwner(ownerId);

        List<SharedTemplate> owned = repository.findByOwner(ownerId);
        assertFalse(owned.isEmpty());
        assertTrue(owned.stream().allMatch(SharedTemplate::isOrphaned));
    }

    /** 物理删除。 */
    @Test
    void shouldDelete() {
        SharedTemplate template = createTemplate(SharedTemplate.Visibility.PUBLIC);
        repository.save(template);

        UUID id = template.getTemplateId();
        repository.delete(id);

        assertTrue(repository.findById(id).isEmpty());
    }

    /** LIST 可见性按指定账户查询。 */
    @Test
    void shouldFindByAllowedAccount() {
        UUID allowedId = UUID.randomUUID();
        SharedTemplate listTemplate = createTemplate(SharedTemplate.Visibility.LIST);
        listTemplate.setAllowedAccounts("[\"" + allowedId + "\",\"" + UUID.randomUUID() + "\"]");
        repository.save(listTemplate);

        // 另一个 LIST 模板，不含 allowedId
        SharedTemplate otherList = createTemplate(SharedTemplate.Visibility.LIST);
        otherList.setAllowedAccounts("[\"" + UUID.randomUUID() + "\"]");
        repository.save(otherList);

        List<SharedTemplate> forAccount = repository.findByAllowedAccount(allowedId);
        assertEquals(1, forAccount.size());
    }

    /** 不允许的账户查 LIST 返回空。 */
    @Test
    void findByAllowedAccount_shouldReturnEmpty_whenNotListed() {
        SharedTemplate listTemplate = createTemplate(SharedTemplate.Visibility.LIST);
        listTemplate.setAllowedAccounts("[\"" + UUID.randomUUID() + "\"]");
        repository.save(listTemplate);

        List<SharedTemplate> forAccount = repository.findByAllowedAccount(UUID.randomUUID());
        assertTrue(forAccount.isEmpty());
    }

    // ===== 辅助 =====

    private SharedTemplate createTemplate(SharedTemplate.Visibility visibility) {
        return SharedTemplate.builder()
                .patternId(UUID.randomUUID())
                .ownerAccountId(ownerId)
                .visibility(visibility)
                .build();
    }
}
