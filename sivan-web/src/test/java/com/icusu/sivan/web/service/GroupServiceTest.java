package com.icusu.sivan.web.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import com.icusu.sivan.infra.agent.repository.ProjectJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private ProjectJpaRepository projectJpaRepository;

    @Mock
    private IAccountRepository accountRepository;

    @Mock
    private IConversationRepository conversationRepository;

    private GroupService groupService;

    private final UUID accountId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private static final String ACCT_SHORT_ID = "brave-moon";

    @TempDir
    Path tempDir;

    private ProjectEntity entity;
    private Account mockAccount;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(projectJpaRepository, accountRepository, conversationRepository);
        ReflectionTestUtils.setField(groupService, "fileRootPath", tempDir.resolve("sivan_data").toString());

        mockAccount = Account.builder()
                .accountId(accountId)
                .shortId(ACCT_SHORT_ID)
                .build();

        entity = ProjectEntity.builder()
                .projectId(groupId)
                .accountId(accountId)
                .name("测试项目")
                .archived(false)
                .undeletable(false)
                .build();
    }

    @Test
    void archive_shouldSetArchivedTrue() {
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));
        when(projectJpaRepository.save(any(ProjectEntity.class))).thenReturn(entity);

        ProjectEntity result = groupService.archive(accountId, groupId);

        assertTrue(result.getArchived());
        assertNotNull(result.getArchivedAt());
    }

    @Test
    void archive_shouldThrowWhenAlreadyArchived() {
        entity.setArchived(true);
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));

        assertThrows(DomainException.class, () -> groupService.archive(accountId, groupId));
        verify(projectJpaRepository, never()).save(any());
    }

    @Test
    void unarchive_shouldSetArchivedFalse() {
        entity.setArchived(true);
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));
        when(projectJpaRepository.save(any(ProjectEntity.class))).thenReturn(entity);

        ProjectEntity result = groupService.unarchive(accountId, groupId);

        assertFalse(result.getArchived());
        assertNull(result.getArchivedAt());
    }

    @Test
    void unarchive_shouldThrowWhenNotArchived() {
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));

        assertThrows(DomainException.class, () -> groupService.unarchive(accountId, groupId));
    }

    @Test
    void delete_shouldNotRemoveFilesByDefault() {
        entity.setLocalPath(tempDir.resolve("project").toString());
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId);

        verify(projectJpaRepository).delete(entity);
    }

    @Test
    void delete_withRemoveFiles_shouldDeleteDirectory() throws Exception {
        Path projectDir = tempDir.resolve("project-to-delete");
        java.nio.file.Files.createDirectories(projectDir);
        entity.setLocalPath(projectDir.toString());
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId, true);

        verify(projectJpaRepository).delete(entity);
        assertFalse(java.nio.file.Files.exists(projectDir), "目录应该被删除");
    }

    @Test
    void delete_shouldThrowWhenNotOwned() {
        UUID otherAccountId = UUID.randomUUID();
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));

        assertThrows(DomainException.class, () -> groupService.delete(otherAccountId, groupId));
    }

    @Test
    void initProjectDirectory_shouldCreateRootDir() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));

        String relativePath = groupService.initProjectDirectory(accountId, "swift-dawn");

        // 返回值应为相对路径 {acctShortId}/{projectShortId}，不绑定 root-path
        assertTrue(relativePath.endsWith(ACCT_SHORT_ID + "/swift-dawn"), "相对路径应以 " + ACCT_SHORT_ID + "/swift-dawn 结尾，实际: " + relativePath);
        // 实际目录创建在 fileRootPath 下
        String fileRoot = (String) ReflectionTestUtils.getField(groupService, "fileRootPath");
        Path root = Path.of(fileRoot, relativePath);
        assertTrue(java.nio.file.Files.exists(root), "项目根目录应存在");
    }

    @Test
    void create_shouldAutoCreateDirsWithShortId() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
        when(projectJpaRepository.countByAccountId(accountId)).thenReturn(0L);
        when(projectJpaRepository.existsByAccountIdAndShortId(eq(accountId), anyString())).thenReturn(false);
        when(projectJpaRepository.save(any(ProjectEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectEntity result = groupService.create(accountId, "测试项目", true);

        assertNotNull(result.getShortId(), "shortId 不应为空");
        assertTrue(result.getShortId().matches("[a-z]+-[a-z]+"), "shortId 格式应为 {adj}-{noun}: " + result.getShortId());
        assertTrue(result.getLocalPathAuto(), "localPathAuto 应为 true");

        // localPath 存相对路径，运行时通过 resolveLocalPath 拼 root-path
        assertTrue(result.getLocalPath().endsWith(ACCT_SHORT_ID + "/" + result.getShortId()), "localPath 应以 {acctShortId}/{projectShortId} 结尾");
        String fileRoot = (String) ReflectionTestUtils.getField(groupService, "fileRootPath");
        Path projectDir = Path.of(fileRoot, result.getLocalPath());
        assertTrue(java.nio.file.Files.exists(projectDir), "项目根目录应存在");
    }

    @Test
    void create_duplicateName_shouldSucceed() {
        when(projectJpaRepository.countByAccountId(accountId)).thenReturn(0L, 1L);
        when(projectJpaRepository.existsByAccountIdAndShortId(eq(accountId), anyString())).thenReturn(false);
        when(projectJpaRepository.save(any(ProjectEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectEntity first = groupService.create(accountId, "同名项目", false);
        ProjectEntity second = groupService.create(accountId, "同名项目", false);

        assertEquals("同名项目", first.getName());
        assertEquals("同名项目", second.getName());
        assertNotEquals(first.getShortId(), second.getShortId(), "同名项目的 shortId 应不同");
    }

    @Test
    void delete_withRemoveFiles_shouldDeleteSubdirs() throws Exception {
        Path projectDir = tempDir.resolve("sivan_data").resolve(ACCT_SHORT_ID).resolve("swift-dawn");
        java.nio.file.Files.createDirectories(projectDir.resolve("data"));
        entity.setLocalPath(projectDir.toString());
        entity.setShortId("swift-dawn");
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId, true);

        verify(projectJpaRepository).delete(entity);
        assertFalse(java.nio.file.Files.exists(projectDir), "目录应该被删除");
    }

    @Test
    void findOwned_shouldReturnEntityWhenOwned() {
        when(projectJpaRepository.findById(groupId)).thenReturn(Optional.of(entity));

        ProjectEntity result = groupService.findOwned(accountId, groupId);

        assertEquals(groupId, result.getProjectId());
    }
}
