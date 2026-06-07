package com.icusu.sivan.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/**
 * 闪回扫描器单元测试。
 */
class FlashbackScannerTest {

    @Mock
    private IMemoryRepository memoryRepository;

    @Mock
    private IEmbeddingService embeddingService;

    private FlashbackScanner scanner;
    private final UUID accountId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    /** 初始化闪回扫描器。 */
    @BeforeEach
    void setUp() {
        lenient().when(embeddingService.isAvailable()).thenReturn(false);
        scanner = new FlashbackScanner(memoryRepository, embeddingService);
    }

    /** 无记忆时扫描结果应为空。 */
    @Test
    void scan_shouldReturnEmpty_whenNoMemories() {
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of());
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    /** 所有记忆保留率高于阈值时扫描结果应为空。 */
    @Test
    void scan_shouldReturnEmpty_whenAllRetentionAboveThreshold() {
        // 15 min ago → retention ~0.84 for USER level → above 0.7 activation threshold
        MemoryEntry fresh = memoryEntry(MemoryLevel.USER, now.minusMinutes(15), 5);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(fresh));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    /** 低保留率的记忆应被扫描为候选项。 */
    @Test
    void scan_shouldReturnCandidates_withLowRetention() {
        MemoryEntry old = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(old));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getRelevanceScore() > 0);
        assertTrue(result.get(0).getRetention() < 0.7);
    }

    /** 重要记忆应获得更高优先级。 */
    @Test
    void scan_shouldPrioritizeImportantMemories() {
        MemoryEntry normal = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        MemoryEntry important = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        important.setImportant(true);

        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(normal, important));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(2, result.size());
        // Important should have higher score
        assertTrue(result.get(0).getRelevanceScore() >= result.get(1).getRelevanceScore());
    }

    /** 扫描结果不应超出限制数量。 */
    @Test
    void scan_shouldRespectLimit() {
        List<MemoryEntry> entries = List.of(
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 1),
                memoryEntry(MemoryLevel.USER, now.minusHours(72), 2),
                memoryEntry(MemoryLevel.USER, now.minusHours(96), 3)
        );
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(entries);
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 2);
        assertEquals(2, result.size());
    }

    /** 上下文匹配应提升相关性评分。 */
    @Test
    void scan_shouldApplyContextBonus() {
        MemoryEntry entry = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        entry.setContent("Java Spring Boot 开发框架");
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(entry));

        List<FlashbackCandidate> withContext = scanner.scan(accountId, "Java 框架", 5);
        List<FlashbackCandidate> withoutContext = scanner.scan(accountId, "", 5);

        assertFalse(withContext.isEmpty());
        assertFalse(withoutContext.isEmpty());
        // Context match should boost score
        assertTrue(withContext.get(0).getRelevanceScore() > withoutContext.get(0).getRelevanceScore());
    }

    /** 已归档的记忆应被过滤掉。 */
    @Test
    void scan_shouldFilterArchivedMemories() {
        MemoryEntry archived = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        archived.setArchived(true);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(archived));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    /** 快速扫描应返回最多 5 条记忆。 */
    @Test
    void quickScan_shouldReturnUpTo5() {
        List<MemoryEntry> entries = List.of(
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 1),
                memoryEntry(MemoryLevel.USER, now.minusHours(72), 2),
                memoryEntry(MemoryLevel.SESSION, now.minusHours(2), 3)
        );
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(entries);
        List<FlashbackCandidate> result = scanner.quickScan(accountId);
        assertEquals(3, result.size());
    }

    /** 扫描结果超过最大候选数时应截断至 10 条。 */
    @Test
    void scan_withExceedsMaxCandidates_shouldCapAt10() {
        List<MemoryEntry> entries = List.of(
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 1),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 2),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 3),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 4),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 5),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 6),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 7),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 8),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 9),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 10),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 11),
                memoryEntry(MemoryLevel.USER, now.minusHours(48), 12)
        );
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(entries);
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 20);
        assertTrue(result.size() <= 10);
    }

    /** 创建测试用的记忆条目。 */
    private MemoryEntry memoryEntry(MemoryLevel level, LocalDateTime lastAccessed, int accessCount) {
        MemoryEntry entry = new MemoryEntry();
        entry.setMemoryId(UUID.randomUUID());
        entry.setLevel(level);
        entry.setLastAccessedAt(lastAccessed);
        entry.setAccessCount(accessCount);
        entry.setContent("test content " + UUID.randomUUID());
        entry.setImportant(false);
        entry.setArchived(false);
        return entry;
    }
}
