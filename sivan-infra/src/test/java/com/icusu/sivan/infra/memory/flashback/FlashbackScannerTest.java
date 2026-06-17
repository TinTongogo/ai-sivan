package com.icusu.sivan.infra.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.node.MemoryNode;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashbackScannerTest {

    @Mock
    private ForestRepository forestRepository;

    @Mock
    private IEmbeddingService embeddingService;

    private FlashbackScanner scanner;
    private final UUID accountId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(embeddingService.isAvailable()).thenReturn(false);
        scanner = new FlashbackScanner(forestRepository, embeddingService);
    }

    private MemoryNode memoryNode(MemoryLevel level, LocalDateTime lastAccessed, int accessCount) {
        MemoryNode node = new MemoryNode(UUID.randomUUID().toString(), "test content " + UUID.randomUUID(), 0.8);
        node.setMetadata(new java.util.LinkedHashMap<>(Map.of(
                "level", level.name(),
                "accessCount", accessCount,
                "lastAccessedAt", lastAccessed.toString(),
                "archived", false,
                "important", false
        )));
        return node;
    }

    @Test
    void scan_shouldReturnEmpty_whenNoMemories() {
        java.util.List<? extends com.icusu.sivan.domain.forest.tree.TreeNode> emptyMem = java.util.List.of();
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)emptyMem);
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    @Test
    void scan_shouldReturnEmpty_whenAllRetentionAboveThreshold() {
        MemoryNode fresh = memoryNode(MemoryLevel.SESSION, now.minusMinutes(15), 5);
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)List.of(fresh));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    @Test
    void scan_shouldReturnCandidates_withLowRetention() {
        MemoryNode old = memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 2);
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)List.of(old));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getRelevanceScore() > 0);
        assertTrue(result.get(0).getRetention() < 0.7);
    }

    @Test
    void scan_shouldPrioritizeImportantMemories() {
        MemoryNode normal = memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 2);
        MemoryNode important = memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 2);
        important.metadata().put("important", true);

        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)List.of(normal, important));
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 5);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getRelevanceScore() >= result.get(1).getRelevanceScore());
    }

    @Test
    void scan_shouldRespectLimit() {
        List<MemoryNode> entries = List.of(
                memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 1),
                memoryNode(MemoryLevel.PROJECT, now.minusHours(144), 2),
                memoryNode(MemoryLevel.PROJECT, now.minusHours(168), 3)
        );
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)entries);
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 2);
        assertEquals(2, result.size());
    }

    @Test
    void scan_shouldApplyContextBonus() {
        MemoryNode entry = new MemoryNode(UUID.randomUUID().toString(), "Java Spring Boot 开发框架", 0.8);
        entry.setMetadata(new java.util.LinkedHashMap<>(Map.of(
                "level", MemoryLevel.PROJECT.name(),
                "accessCount", 2,
                "lastAccessedAt", now.minusHours(120).toString(),
                "archived", false,
                "important", false
        )));
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)List.of(entry));

        List<FlashbackCandidate> withContext = scanner.scan(accountId, "Java 框架", 5);
        assertFalse(withContext.isEmpty());
    }

    @Test
    void scan_shouldFilterArchivedMemories() {
        MemoryNode archived = memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 2);
        archived.metadata().put("archived", true);
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)List.of(archived));
        assertTrue(scanner.scan(accountId, "", 5).isEmpty());
    }

    @Test
    void quickScan_shouldReturnUpTo5() {
        List<MemoryNode> entries = List.of(
                memoryNode(MemoryLevel.PROJECT, now.minusHours(120), 1),
                memoryNode(MemoryLevel.PROJECT, now.minusHours(144), 2),
                memoryNode(MemoryLevel.SESSION, now.minusHours(6), 1)
        );
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)entries);
        List<FlashbackCandidate> result = scanner.quickScan(accountId);
        assertEquals(3, result.size());
    }

    @Test
    void scan_withExceedsMaxCandidates_shouldCapAt10() {
        List<MemoryNode> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(memoryNode(MemoryLevel.PROJECT, now.minusHours(120), i + 1));
        }
        when(forestRepository.findNodesByTypeAndAccount(accountId, "memory", 20)).thenReturn((java.util.List)entries);
        List<FlashbackCandidate> result = scanner.scan(accountId, "", 20);
        assertTrue(result.size() <= 10);
    }
}
