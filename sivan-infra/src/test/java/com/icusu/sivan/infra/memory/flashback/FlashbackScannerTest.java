package com.icusu.sivan.infra.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.domain.memory.flashback.FlashbackCandidate;
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
class FlashbackScannerTest {

    @Mock
    private IMemoryRepository memoryRepository;

    @Mock
    private IEmbeddingService embeddingService;

    private FlashbackScanner scanner;
    private final UUID accountId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(embeddingService.isAvailable()).thenReturn(false);
        scanner = new FlashbackScanner(memoryRepository, embeddingService);
    }

    @Test
    void scan_shouldReturnEmpty_whenNoMemories() {
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of());
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    @Test
    void scan_shouldReturnEmpty_whenAllRetentionAboveThreshold() {
        MemoryEntry fresh = memoryEntry(MemoryLevel.USER, now.minusMinutes(15), 5);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(fresh));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    @Test
    void scan_shouldReturnCandidates_withLowRetention() {
        MemoryEntry old = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(old));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getRelevanceScore() > 0);
        assertTrue(result.get(0).getRetention() < 0.7);
    }

    @Test
    void scan_shouldPrioritizeImportantMemories() {
        MemoryEntry normal = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        MemoryEntry important = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        important.setImportant(true);

        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(normal, important));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getRelevanceScore() >= result.get(1).getRelevanceScore());
    }

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

    @Test
    void scan_shouldApplyContextBonus() {
        MemoryEntry entry = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        entry.setContent("Java Spring Boot 开发框架");
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(entry));

        List<FlashbackCandidate> withContext = scanner.scan(accountId, "Java 框架", 5);
        List<FlashbackCandidate> withoutContext = scanner.scan(accountId, "", 5);

        assertFalse(withContext.isEmpty());
        assertFalse(withoutContext.isEmpty());
        assertTrue(withContext.get(0).getRelevanceScore() > withoutContext.get(0).getRelevanceScore());
    }

    @Test
    void scan_shouldFilterArchivedMemories() {
        MemoryEntry archived = memoryEntry(MemoryLevel.USER, now.minusHours(48), 2);
        archived.setArchived(true);
        when(memoryRepository.findAllByAccount(accountId)).thenReturn(List.of(archived));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

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
