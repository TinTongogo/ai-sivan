package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.SharedTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedTemplateServiceTest {

    @Mock
    private ISharedTemplateRepository sharedTemplateRepository;
    @Mock
    private IInstinctPatternRepository patternRepository;

    @Captor
    private ArgumentCaptor<SharedTemplate> templateCaptor;

    private SharedTemplateService service;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID patternId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SharedTemplateService(sharedTemplateRepository, patternRepository);
    }

    private InstinctPattern createPattern() {
        return InstinctPattern.builder()
                .patternId(patternId)
                .accountId(ownerId)
                .topologyJson("{\"phases\":[{\"name\":\"A\"}]}")
                .active(true)
                .build();
    }

    // ===== share =====

    @Test
    void share_shouldCreateSharedTemplate() {
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(createPattern()));

        SharedTemplate result = service.share(patternId, ownerId, SharedTemplate.Visibility.PUBLIC);

        assertNotNull(result);
        assertEquals(SharedTemplate.Visibility.PUBLIC, result.getVisibility());
        assertEquals(ownerId, result.getOwnerAccountId());
        assertEquals("NORMAL", result.getQuality());
        verify(sharedTemplateRepository).save(any());
    }

    @Test
    void share_shouldThrow_whenPatternNotFound() {
        when(patternRepository.findById(patternId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.share(patternId, ownerId, SharedTemplate.Visibility.PUBLIC));
        verify(sharedTemplateRepository, never()).save(any());
    }

    @Test
    void share_shouldThrow_whenNotOwner() {
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(patternId)
                .accountId(UUID.randomUUID()) // different owner
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(pattern));

        assertThrows(IllegalArgumentException.class,
                () -> service.share(patternId, ownerId, SharedTemplate.Visibility.PUBLIC));
        verify(sharedTemplateRepository, never()).save(any());
    }

    @Test
    void share_shouldAllowTenantVisibility() {
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(createPattern()));

        SharedTemplate result = service.share(patternId, ownerId, SharedTemplate.Visibility.TENANT);

        assertEquals(SharedTemplate.Visibility.TENANT, result.getVisibility());
    }

    @Test
    void share_shouldAllowListVisibility() {
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(createPattern()));

        SharedTemplate result = service.share(patternId, ownerId, SharedTemplate.Visibility.LIST);

        assertEquals(SharedTemplate.Visibility.LIST, result.getVisibility());
    }

    // ===== unshare =====

    @Test
    void unshare_shouldDeleteTemplate() {
        SharedTemplate template = SharedTemplate.builder()
                .templateId(UUID.randomUUID())
                .ownerAccountId(ownerId)
                .build();
        when(sharedTemplateRepository.findById(template.getTemplateId())).thenReturn(Optional.of(template));

        service.unshare(template.getTemplateId(), ownerId);

        verify(sharedTemplateRepository).delete(template.getTemplateId());
    }

    @Test
    void unshare_shouldThrow_whenNotOwner() {
        UUID templateId = UUID.randomUUID();
        SharedTemplate template = SharedTemplate.builder()
                .templateId(templateId)
                .ownerAccountId(UUID.randomUUID()) // different owner
                .build();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        assertThrows(IllegalArgumentException.class,
                () -> service.unshare(templateId, ownerId));
        verify(sharedTemplateRepository, never()).delete(any());
    }

    @Test
    void unshare_shouldDoNothing_whenTemplateNotFound() {
        UUID templateId = UUID.randomUUID();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.empty());

        service.unshare(templateId, ownerId);

        verify(sharedTemplateRepository, never()).delete(any());
    }

    // ===== findAccessibleTemplates =====

    @Test
    void findAccessibleTemplates_shouldReturnAllTypes() {
        UUID accountId = UUID.randomUUID();
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId))
                .thenReturn(List.of(SharedTemplate.builder().build()));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId))
                .thenReturn(List.of(SharedTemplate.builder().build()));
        when(sharedTemplateRepository.findByAllowedAccount(accountId))
                .thenReturn(List.of(SharedTemplate.builder().build()));

        List<SharedTemplate> result = service.findAccessibleTemplates(accountId);

        assertEquals(3, result.size());
    }

    @Test
    void findAccessibleTemplates_shouldReturnEmpty_whenNoneAccessible() {
        UUID accountId = UUID.randomUUID();
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId))
                .thenReturn(List.of());
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId))
                .thenReturn(List.of());
        when(sharedTemplateRepository.findByAllowedAccount(accountId))
                .thenReturn(List.of());

        List<SharedTemplate> result = service.findAccessibleTemplates(accountId);

        assertTrue(result.isEmpty());
    }

    // ===== recordUsage =====

    @Test
    void recordUsage_shouldIncrementCounts() {
        UUID templateId = UUID.randomUUID();
        SharedTemplate template = SharedTemplate.builder()
                .templateId(templateId)
                .useCount(5)
                .successCount(3)
                .quality("NORMAL")
                .build();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        service.recordUsage(templateId, true);

        assertEquals(6, template.getUseCount());
        assertEquals(4, template.getSuccessCount());
        verify(sharedTemplateRepository, times(1)).save(template);
    }

    @Test
    void recordUsage_shouldDoNothing_whenTemplateNotFound() {
        UUID templateId = UUID.randomUUID();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.empty());

        service.recordUsage(templateId, true);

        verify(sharedTemplateRepository, never()).save(any());
    }

    // ===== 质量降级 =====

    @Test
    void recordUsage_shouldDowngrade_whenSuccessRateBelowThreshold() {
        UUID templateId = UUID.randomUUID();
        SharedTemplate template = SharedTemplate.builder()
                .templateId(templateId)
                .useCount(20)
                .successCount(5)  // 25% < 30%
                .quality("NORMAL")
                .build();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        service.recordUsage(templateId, false);

        assertEquals("LOW_QUALITY", template.getQuality());
        verify(sharedTemplateRepository, times(2)).save(template);
    }

    @Test
    void recordUsage_shouldNotDowngrade_whenSamplesInsufficient() {
        UUID templateId = UUID.randomUUID();
        SharedTemplate template = SharedTemplate.builder()
                .templateId(templateId)
                .useCount(10)
                .successCount(2)  // 20% < 30% but only 10 samples
                .quality("NORMAL")
                .build();
        when(sharedTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        service.recordUsage(templateId, false);

        assertEquals("NORMAL", template.getQuality());
        verify(sharedTemplateRepository, times(1)).save(template); // save but not twice (no quality change)
    }

    // ===== 隐私扫描 =====

    @Test
    void share_shouldReject_whenTopologyContainsSensitiveContent() {
        String longText = "a".repeat(100);
        InstinctPattern sensitive = InstinctPattern.builder()
                .patternId(patternId)
                .accountId(ownerId)
                .topologyJson("{\"prompt\":\"" + longText + "\"}")
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(sensitive));

        assertThrows(IllegalArgumentException.class,
                () -> service.share(patternId, ownerId, SharedTemplate.Visibility.PUBLIC));
    }

    @Test
    void containsSensitiveContent_shouldReturnFalse_forNull() {
        assertFalse(SharedTemplateService.containsSensitiveContent(null));
    }

    @Test
    void containsSensitiveContent_shouldReturnFalse_forShortJson() {
        assertFalse(SharedTemplateService.containsSensitiveContent("{\"a\":\"b\"}"));
    }

    @Test
    void containsSensitiveContent_shouldReturnTrue_forLongContinuousText() {
        String longText = "x".repeat(60);
        assertTrue(SharedTemplateService.containsSensitiveContent(
                "{\"content\":\"" + longText + "\"}"));
    }

    @Test
    void containsSensitiveContent_shouldReturnFalse_forStructuredTopology() {
        String topology = "{\"phases\":[{\"name\":\"分析\",\"agents\":[\"研究员\",\"分析师\"]}]}";
        assertFalse(SharedTemplateService.containsSensitiveContent(topology));
    }
}
