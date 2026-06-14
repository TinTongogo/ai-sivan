package com.icusu.sivan.application.service;

import com.icusu.sivan.application.service.GroupService;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.agent.Project;
import com.icusu.sivan.domain.agent.repository.IProjectRepository;
import com.icusu.sivan.domain.conversation.IConversationRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private IProjectRepository projectRepository;

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

    private Project project;
    private Account mockAccount;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(projectRepository, accountRepository, conversationRepository);
        ReflectionTestUtils.setField(groupService, "fileRootPath", tempDir.resolve("sivan_data").toString());

        mockAccount = Account.builder()
                .accountId(accountId)
                .shortId(ACCT_SHORT_ID)
                .build();

        project = Project.builder()
                .projectId(groupId)
                .accountId(accountId)
                .name("测试项目")
                .archived(false)
                .undeletable(false)
                .build();
    }

    @Test
    void archive_shouldSetArchivedTrue() {
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = groupService.archive(accountId, groupId);

        assertTrue(result.getArchived());
        assertNotNull(result.getArchivedAt());
    }

    @Test
    void archive_shouldThrowWhenAlreadyArchived() {
        project.setArchived(true);
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));

        assertThrows(DomainException.class, () -> groupService.archive(accountId, groupId));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void unarchive_shouldSetArchivedFalse() {
        project.setArchived(true);
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = groupService.unarchive(accountId, groupId);

        assertFalse(result.getArchived());
        assertNull(result.getArchivedAt());
    }

    @Test
    void unarchive_shouldThrowWhenNotArchived() {
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));

        assertThrows(DomainException.class, () -> groupService.unarchive(accountId, groupId));
    }

    @Test
    void delete_shouldNotRemoveFilesByDefault() {
        project.setLocalPath(tempDir.resolve("project").toString());
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId);

        verify(projectRepository).delete(project);
    }

    @Test
    void delete_withRemoveFiles_shouldDeleteDirectory() throws Exception {
        Path projectDir = tempDir.resolve("project-to-delete");
        java.nio.file.Files.createDirectories(projectDir);
        project.setLocalPath(projectDir.toString());
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId, true);

        verify(projectRepository).delete(project);
        assertFalse(java.nio.file.Files.exists(projectDir), "目录应该被删除");
    }

    @Test
    void delete_shouldThrowWhenNotOwned() {
        UUID otherAccountId = UUID.randomUUID();
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));

        assertThrows(DomainException.class, () -> groupService.delete(otherAccountId, groupId));
    }

    @Test
    void initProjectDirectory_shouldCreateRootDir() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));

        String relativePath = groupService.initProjectDirectory(accountId, "swift-dawn");

        assertTrue(relativePath.endsWith(ACCT_SHORT_ID + "/swift-dawn"), "相对路径应以 " + ACCT_SHORT_ID + "/swift-dawn 结尾，实际: " + relativePath);
        String fileRoot = (String) ReflectionTestUtils.getField(groupService, "fileRootPath");
        Path root = Path.of(fileRoot, relativePath);
        assertTrue(java.nio.file.Files.exists(root), "项目根目录应存在");
    }

    @Test
    void create_shouldAutoCreateDirsWithShortId() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
        when(projectRepository.countByAccountId(accountId)).thenReturn(0L);
        when(projectRepository.existsByAccountIdAndShortId(eq(accountId), anyString())).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = groupService.create(accountId, "测试项目", true);

        assertNotNull(result.getShortId(), "shortId 不应为空");
        assertTrue(result.getShortId().matches("[a-z]+-[a-z]+"), "shortId 格式应为 {adj}-{noun}: " + result.getShortId());
        assertTrue(result.getLocalPathAuto(), "localPathAuto 应为 true");

        assertTrue(result.getLocalPath().endsWith(ACCT_SHORT_ID + "/" + result.getShortId()), "localPath 应以 {acctShortId}/{projectShortId} 结尾");
        String fileRoot = (String) ReflectionTestUtils.getField(groupService, "fileRootPath");
        Path projectDir = Path.of(fileRoot, result.getLocalPath());
        assertTrue(java.nio.file.Files.exists(projectDir), "项目根目录应存在");
    }

    @Test
    void create_duplicateName_shouldSucceed() {
        when(projectRepository.countByAccountId(accountId)).thenReturn(0L, 1L);
        when(projectRepository.existsByAccountIdAndShortId(eq(accountId), anyString())).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project first = groupService.create(accountId, "同名项目", false);
        Project second = groupService.create(accountId, "同名项目", false);

        assertEquals("同名项目", first.getName());
        assertEquals("同名项目", second.getName());
        assertNotEquals(first.getShortId(), second.getShortId(), "同名项目的 shortId 应不同");
    }

    @Test
    void delete_withRemoveFiles_shouldDeleteSubdirs() throws Exception {
        Path projectDir = tempDir.resolve("sivan_data").resolve(ACCT_SHORT_ID).resolve("swift-dawn");
        java.nio.file.Files.createDirectories(projectDir.resolve("data"));
        project.setLocalPath(projectDir.toString());
        project.setShortId("swift-dawn");
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));
        when(conversationRepository.findAllByAccountAndProject(accountId, groupId)).thenReturn(List.of());

        groupService.delete(accountId, groupId, true);

        verify(projectRepository).delete(project);
        assertFalse(java.nio.file.Files.exists(projectDir), "目录应该被删除");
    }

    @Test
    void findOwned_shouldReturnProjectWhenOwned() {
        when(projectRepository.findById(groupId)).thenReturn(Optional.of(project));

        Project result = groupService.findOwned(accountId, groupId);

        assertEquals(groupId, result.getProjectId());
    }
}
