package com.icusu.sivan.application.service;

import com.icusu.sivan.application.memory.dto.CreateMemoryRequest;
import com.icusu.sivan.application.memory.dto.MemoryResponse;
import com.icusu.sivan.application.memory.dto.UpdateMemoryRequest;
import com.icusu.sivan.application.service.MemoryConsolidator;
import com.icusu.sivan.application.service.MemoryService;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 记忆服务测试。 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private IMemoryRepository memoryRepository;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private IMessageRepository messageRepository;
    @Mock
    private IConversationRepository conversationRepository;
    @Mock
    private FileStoragePort fileStoragePort;
    @Mock
    private IEmbeddingService embeddingService;
    @Mock
    private MemoryConsolidator memoryConsolidator;

    private MemoryService memoryService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    /** 初始化测试环境。 */
    void setUp() {
        memoryService = new MemoryService(memoryRepository, modelRouter, messageRepository, conversationRepository, fileStoragePort, embeddingService, memoryConsolidator);
    }

    @Test
    /** 创建记忆成功。 */
    void create_shouldSucceed() {
        CreateMemoryRequest request = new CreateMemoryRequest();
        request.setContent("用户喜欢Python编程");
        request.setLevel("SESSION");
        request.setScopeId("conv-123");

        when(memoryRepository.save(any(MemoryEntry.class))).thenReturn(UUID.randomUUID());

        MemoryResponse response = memoryService.create(accountId, request);

        assertEquals("用户喜欢Python编程", response.getContent());
        assertEquals("SESSION", response.getLevel());
        assertEquals("conv-123", response.getScopeId());
        verify(memoryRepository).save(any(MemoryEntry.class));
    }

    @Test
    /** 创建记忆默认使用 SESSION 级别。 */
    void create_shouldDefaultLevelToSession() {
        CreateMemoryRequest request = new CreateMemoryRequest();
        request.setContent("测试记忆");

        when(memoryRepository.save(any(MemoryEntry.class))).thenReturn(UUID.randomUUID());

        MemoryResponse response = memoryService.create(accountId, request);

        assertEquals("SESSION", response.getLevel());
    }

    @Test
    /** 根据 ID 获取记忆。 */
    void getById_shouldReturnMemory() {
        UUID memoryId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder().memoryId(memoryId).accountId(accountId).content("重要记忆").level(MemoryLevel.USER).build();

        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.of(entry));

        MemoryResponse response = memoryService.getById(accountId, memoryId);

        assertEquals("重要记忆", response.getContent());
        assertEquals("USER", response.getLevel());
    }

    @Test
    /** 获取不存在的记忆应抛出异常。 */
    void getById_shouldThrowWhenNotOwned() {
        UUID memoryId = UUID.randomUUID();
        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> memoryService.getById(accountId, memoryId));
    }

    @Test
    /** 列出所有记忆。 */
    void list_shouldReturnAllWhenNoFilters() {
        MemoryEntry entry = MemoryEntry.builder().memoryId(UUID.randomUUID()).accountId(accountId).content("记忆1").build();

        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(entry));

        List<MemoryResponse> list = memoryService.list(accountId, null, null);

        assertEquals(1, list.size());
        assertEquals("记忆1", list.get(0).getContent());
        verify(memoryRepository).findAllByAccount(accountId);
    }

    @Test
    /** 按级别和作用域过滤记忆。 */
    void list_shouldFilterByLevelAndScope() {
        String scopeId = "scope-1";
        MemoryEntry entry = MemoryEntry.builder().memoryId(UUID.randomUUID()).accountId(accountId).content("会话记忆").level(MemoryLevel.SESSION).scopeId(scopeId).build();

        when(memoryRepository.findByLevelAndScope(accountId, MemoryLevel.SESSION, scopeId)).thenReturn(List.of(entry));

        List<MemoryResponse> list = memoryService.list(accountId, "SESSION", scopeId);

        assertEquals(1, list.size());
        assertEquals("会话记忆", list.get(0).getContent());
    }

    @Test
    /** 更新记忆内容。 */
    void update_shouldModifyFields() {
        UUID memoryId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder().memoryId(memoryId).accountId(accountId).content("旧内容").important(false).archived(false).build();

        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.of(entry));

        UpdateMemoryRequest request = new UpdateMemoryRequest();
        request.setContent("新内容");
        request.setImportant(true);
        request.setArchived(true);

        MemoryResponse response = memoryService.update(accountId, memoryId, request);

        assertEquals("新内容", response.getContent());
        assertTrue(response.getImportant());
        assertTrue(response.getArchived());
        verify(memoryRepository).update(entry);
    }

    @Test
    /** 删除记忆。 */
    void delete_shouldRemoveMemory() {
        UUID memoryId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder().memoryId(memoryId).accountId(accountId).build();

        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.of(entry));

        memoryService.delete(accountId, memoryId);

        verify(memoryRepository).delete(memoryId);
    }

    @Test
    /** 切换重要标记 — false → true。 */
    void toggleImportant_shouldSetImportant() {
        UUID memoryId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder().memoryId(memoryId).accountId(accountId).important(false).build();

        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.of(entry));

        MemoryResponse response = memoryService.toggleImportant(accountId, memoryId);

        assertTrue(response.getImportant());
        verify(memoryRepository).update(entry);
    }

    @Test
    /** 切换重要标记 — true → false（取消重要）。 */
    void toggleImportant_shouldUnsetImportant() {
        UUID memoryId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.builder().memoryId(memoryId).accountId(accountId).important(true).build();

        when(memoryRepository.findByIdAndAccount(memoryId, accountId)).thenReturn(Optional.of(entry));

        MemoryResponse response = memoryService.toggleImportant(accountId, memoryId);

        assertFalse(response.getImportant());
        verify(memoryRepository).update(entry);
    }

    @Test
    /** 列出重要记忆。 */
    void listImportant_shouldReturnMarkedEntries() {
        MemoryEntry entry = MemoryEntry.builder().memoryId(UUID.randomUUID()).accountId(accountId).content("重要内容").important(true).build();

        when(memoryRepository.findImportant(accountId, null, 20)).thenReturn(List.of(entry));

        List<MemoryResponse> list = memoryService.listImportant(accountId, null);

        assertEquals(1, list.size());
        assertTrue(list.get(0).getImportant());
    }

    @Test
    /** 获取记忆统计信息。 */
    void getStats_shouldReturnCount() {
        when(memoryRepository.countByAccount(accountId)).thenReturn(5L);

        Map<String, Object> stats = memoryService.getStats(accountId);

        assertEquals(5L, stats.get("totalCount"));
    }
}
