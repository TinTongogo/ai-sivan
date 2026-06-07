package com.icusu.sivan.web.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ModelCapability;
import com.icusu.sivan.web.model.dto.CreateLlmProviderRequest;
import com.icusu.sivan.web.model.dto.LlmProviderResponse;
import com.icusu.sivan.web.model.dto.ModelCapabilityInfo;
import com.icusu.sivan.web.model.dto.UpdateLlmProviderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderServiceTest {

    @Mock private ILlmProviderRepository repository;
    @Mock private ModelRouter modelRouter;
    @Mock private ModelCapabilityRegistry capabilityRegistry;

    private LlmProviderService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(repository, objectMapper, modelRouter, capabilityRegistry);
    }

    private static LlmProvider aProvider(UUID accountId) {
        return LlmProvider.builder()
                .providerId(UUID.randomUUID())
                .accountId(accountId)
                .name("测试提供商")
                .providerType("openai")
                .apiKey("sk-test")
                .baseUrl("https://api.openai.com")
                .models("gpt-4o")
                .capabilities("chat,tool")
                .active(true)
                
                .temperature(0.7)
                .tags("chat")
                .build();
    }

    @Test
    void getAllCapabilities_返回全部能力() {
        List<ModelCapabilityInfo> caps = service.getAllCapabilities();
        assertFalse(caps.isEmpty());
        assertTrue(caps.stream().anyMatch(c -> c.getCode().equals("vision")));
    }

    @Test
    void getDefaultCapabilities_按providerType() {
        when(capabilityRegistry.getDefaults("openai"))
                .thenReturn(EnumSet.of(ModelCapability.STREAMING, ModelCapability.TOOL_USE));

        List<String> caps = service.getDefaultCapabilities("openai");
        assertEquals(2, caps.size());
        assertTrue(caps.contains("streaming"));
    }

    @Test
    void inferCapabilities_委托到registry() {
        when(capabilityRegistry.infer("gpt-4o", "openai"))
                .thenReturn(EnumSet.of(ModelCapability.STREAMING, ModelCapability.VISION));

        List<String> caps = service.inferCapabilities("gpt-4o", "openai");
        assertEquals(2, caps.size());
    }

    @Test
    void create_成功创建() {
        CreateLlmProviderRequest request = new CreateLlmProviderRequest();
        request.setName("新提供商");
        request.setProviderType("openai");
        request.setApiKey("sk-key");
        request.setBaseUrl("https://api.openai.com");
        request.setModel("gpt-4o");
        request.setCapabilities("chat,tool");

        LlmProviderResponse response = service.create(UUID.randomUUID(), request);
        assertNotNull(response);
        assertEquals("新提供商", response.getName());
        verify(repository, times(2)).save(any());
    }

    @Test
    void create_capabilities缺失抛出异常() {
        CreateLlmProviderRequest request = new CreateLlmProviderRequest();
        request.setName("n");
        request.setProviderType("openai");

        assertThrows(DomainException.class, () -> service.create(UUID.randomUUID(), request));
        verify(repository, never()).save(any());
    }

    @Test
    void create_指定上下文长度() {
        CreateLlmProviderRequest request = new CreateLlmProviderRequest();
        request.setName("n");
        request.setProviderType("openai");
        request.setApiKey("sk-key");
        request.setBaseUrl("https://api.openai.com");
        request.setModel("gpt-4o");
        request.setCapabilities("chat");
        request.setContextLength(8192);

        service.create(UUID.randomUUID(), request);
        verify(repository, times(2)).save(any());
    }

    @Test
    void list_返回全部() {
        UUID accountId = UUID.randomUUID();
        when(repository.findAllByAccount(accountId)).thenReturn(List.of(
                aProvider(accountId), aProvider(accountId)));

        var result = service.list(accountId);
        assertEquals(2, result.size());
    }

    @Test
    void getById_返回提供商() {
        UUID accountId = UUID.randomUUID();
        LlmProvider provider = aProvider(accountId);
        when(repository.findById(provider.getProviderId())).thenReturn(Optional.of(provider));

        LlmProviderResponse response = service.getById(accountId, provider.getProviderId());
        assertNotNull(response);
        assertEquals(provider.getProviderId(), response.getProviderId());
    }

    @Test
    void getById_不存在抛出异常() {
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThrows(DomainException.class,
                () -> service.getById(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void delete_删除成功() {
        UUID accountId = UUID.randomUUID();
        LlmProvider provider = aProvider(accountId);
        when(repository.findById(provider.getProviderId())).thenReturn(Optional.of(provider));

        service.delete(accountId, provider.getProviderId());
        verify(repository).delete(provider.getProviderId());
    }

    @Test
    void update_更新成功() {
        UUID accountId = UUID.randomUUID();
        LlmProvider provider = aProvider(accountId);
        when(repository.findById(provider.getProviderId())).thenReturn(Optional.of(provider));

        UpdateLlmProviderRequest request = new UpdateLlmProviderRequest();
        request.setName("新名称");
        request.setTemperature(0.5);

        service.update(accountId, provider.getProviderId(), request);
        verify(repository).save(provider);
        verify(modelRouter).evictCache(provider.getProviderId());
    }

    @Test
    void setDefault_非对话标签允许设为默认() {
        UUID accountId = UUID.randomUUID();
        LlmProvider provider = aProvider(accountId);
        provider.setTags("embedding"); // 非 chat 标签（如 embedding）也可设为默认
        when(repository.findById(provider.getProviderId())).thenReturn(Optional.of(provider));

        assertDoesNotThrow(() -> service.setDefault(accountId, provider.getProviderId(), true));
    }
}
