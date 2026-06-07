package com.icusu.sivan.agent.model;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultModelRouterTest {

    @Mock
    private ILlmProviderRepository providerRepository;
    @Mock
    private ModelCapabilityRegistry capabilityRegistry;
    private DefaultModelRouter router;
    private final UUID accountId = UUID.randomUUID();

    private LlmProvider chatProvider;
    private LlmProvider embeddingProvider;

    @BeforeEach
    void setUp() {
        router = new DefaultModelRouter(providerRepository, capabilityRegistry);

        chatProvider = LlmProvider.builder()
                .providerId(UUID.randomUUID())
                .accountId(accountId)
                .name("ChatGPT")
                .providerType("openai")
                .baseUrl("https://api.openai.com/v1")
                .apiKey("sk-test")
                .models("gpt-4o")
                .tags("chat")
                .active(true)
                .isChatDefault(true)
                .build();

        embeddingProvider = LlmProvider.builder()
                .providerId(UUID.randomUUID())
                .accountId(accountId)
                .name("BGE-M3")
                .providerType("ollama")
                .baseUrl("http://localhost:11434")
                .models("bge-m3:latest")
                .tags("embedding")
                .active(true)
                .build();
    }

    // ====== getDefaultProvider (chat) ======

    @Test
    void getDefaultProvider_shouldFindChatByDefault() {
        when(providerRepository.findDefaultByAccount(accountId))
                .thenReturn(Optional.of(chatProvider));

        LlmProvider result = router.getDefaultProvider(accountId);
        assertEquals("ChatGPT", result.getName());
        assertTrue(result.getTags().contains("chat"));
    }

    @Test
    void getDefaultProvider_noDefault_shouldFallbackToAccountTags() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "chat"))
                .thenReturn(List.of(chatProvider));

        LlmProvider result = router.getDefaultProvider(accountId);
        assertNotNull(result);
    }

    @Test
    void getDefaultProvider_none_shouldThrow() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "chat"))
                .thenReturn(List.of());
        when(providerRepository.findActiveByAccount(accountId)).thenReturn(List.of());

        assertThrows(DomainException.class, () -> router.getDefaultProvider(accountId));
    }

    // ====== getEmbeddingProvider ======

    @Test
    void getEmbeddingProvider_shouldFindByTags() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "embedding"))
                .thenReturn(List.of(embeddingProvider));

        LlmProvider result = router.getEmbeddingProvider(accountId);
        assertEquals("BGE-M3", result.getName());
        assertTrue(result.getTags().contains("embedding"));
    }

    @Test
    void getEmbeddingProvider_defaultWithEmbedding_shouldPrioritize() {
        LlmProvider defaultEmbed = LlmProvider.builder()
                .providerId(UUID.randomUUID())
                .accountId(accountId)
                .name("Default-Embed")
                .baseUrl("http://localhost:11434")
                .models("bge-m3")
                .tags("embedding")
                .active(true)
                .isChatDefault(true)
                .build();
        when(providerRepository.findDefaultByAccount(accountId))
                .thenReturn(Optional.of(defaultEmbed));

        LlmProvider result = router.getEmbeddingProvider(accountId);
        assertEquals("Default-Embed", result.getName());
    }

    @Test
    void getEmbeddingProvider_none_shouldThrow() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "embedding"))
                .thenReturn(List.of());
        when(providerRepository.findActiveByAccount(accountId)).thenReturn(List.of());

        assertThrows(DomainException.class, () -> router.getEmbeddingProvider(accountId));
    }

    // ====== getDefaultModel / getEmbeddingModel ======

    @Test
    void getDefaultModel_shouldReturnOpenAiModel() {
        when(providerRepository.findDefaultByAccount(accountId))
                .thenReturn(Optional.of(chatProvider));

        var model = router.getDefaultModel(accountId);
        assertNotNull(model);
        assertEquals("gpt-4o", model.modelId());
    }

    @Test
    void getEmbeddingModel_shouldReturnOpenAiModel() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "embedding"))
                .thenReturn(List.of(embeddingProvider));

        var model = router.getEmbeddingModel(accountId);
        assertNotNull(model);
        assertEquals("bge-m3:latest", model.modelId());
    }

    // ====== getModel (by providerId) ======

    @Test
    void getModel_withProviderId_shouldCache() {
        when(providerRepository.findById(embeddingProvider.getProviderId()))
                .thenReturn(Optional.of(embeddingProvider));

        var model1 = router.getModel(embeddingProvider.getProviderId());
        var model2 = router.getModel(embeddingProvider.getProviderId());
        assertSame(model1, model2); // 缓存命中
    }

    // ====== Chat 不匹配 Embedding ======

    @Test
    void getDefaultProvider_shouldNotReturnEmbeddingOnlyProvider() {
        // embedding provider 没有 chat tag
        LlmProvider embedOnly = LlmProvider.builder()
                .providerId(UUID.randomUUID())
                .accountId(accountId)
                .name("Embed-Only")
                .baseUrl("http://localhost:11434")
                .models("bge-m3")
                .tags("embedding")
                .active(true)
                .isChatDefault(true)
                .build();
        when(providerRepository.findDefaultByAccount(accountId))
                .thenReturn(Optional.of(embedOnly));

        // 默认 provider 没有 chat tag → 跳过，fallback 到 active
        when(providerRepository.findByAccountAndTagsContains(accountId, "chat"))
                .thenReturn(List.of());
        when(providerRepository.findActiveByAccount(accountId)).thenReturn(List.of(embedOnly));

        assertThrows(DomainException.class, () -> router.getDefaultProvider(accountId));
    }

    @Test
    void getEmbeddingProvider_shouldNotReturnChatOnlyProvider() {
        when(providerRepository.findDefaultByAccount(accountId)).thenReturn(Optional.empty());
        when(providerRepository.findByAccountAndTagsContains(accountId, "embedding"))
                .thenReturn(List.of());
        when(providerRepository.findActiveByAccount(accountId)).thenReturn(List.of(chatProvider));

        assertThrows(DomainException.class, () -> router.getEmbeddingProvider(accountId));
    }

    // ====== evictCache ======

    @Test
    void evictCache_shouldClearCachedModel() {
        when(providerRepository.findById(embeddingProvider.getProviderId()))
                .thenReturn(Optional.of(embeddingProvider));
        var model1 = router.getModel(embeddingProvider.getProviderId());

        router.evictCache(embeddingProvider.getProviderId());
        when(providerRepository.findById(embeddingProvider.getProviderId()))
                .thenReturn(Optional.of(embeddingProvider));
        var model2 = router.getModel(embeddingProvider.getProviderId());

        assertNotSame(model1, model2);
    }
}
