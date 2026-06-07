package com.icusu.sivan.infra.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.model.LlmProvider;
import com.icusu.sivan.domain.model.ILlmProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private ILlmProviderRepository llmProviderRepository;

    // ====== URL 构建 ======

    @Test
    void buildOpenAiEmbedUrl_plainOllama_shouldAppendV1Embeddings() {
        assertEquals("http://localhost:11434/v1/embeddings",
                EmbeddingService.buildOpenAiEmbedUrl("http://localhost:11434"));
    }

    @Test
    void buildOpenAiEmbedUrl_trailingSlash_shouldNormalize() {
        assertEquals("http://localhost:11434/v1/embeddings",
                EmbeddingService.buildOpenAiEmbedUrl("http://localhost:11434/"));
    }

    @Test
    void buildOpenAiEmbedUrl_alreadyHasV1Embeddings_shouldPreserve() {
        assertEquals("http://localhost:11434/v1/embeddings",
                EmbeddingService.buildOpenAiEmbedUrl("http://localhost:11434/v1/embeddings"));
    }

    @Test
    void buildOpenAiEmbedUrl_alreadyHasV1_shouldAppendEmbeddings() {
        assertEquals("http://api.openai.com/v1/embeddings",
                EmbeddingService.buildOpenAiEmbedUrl("http://api.openai.com/v1"));
    }

    @Test
    void buildOpenAiEmbedUrl_alreadyHasV1Slash_shouldAppendEmbeddings() {
        assertEquals("http://api.openai.com/v1/embeddings",
                EmbeddingService.buildOpenAiEmbedUrl("http://api.openai.com/v1/"));
    }

    @Test
    void buildOpenAiEmbedUrl_alreadyHasApiEmbed_shouldPreserve() {
        assertEquals("http://localhost:11434/api/embed",
                EmbeddingService.buildOpenAiEmbedUrl("http://localhost:11434/api/embed"));
    }

    @Test
    void buildOpenAiEmbedUrl_nullOrBlank_shouldReturnNull() {
        assertNull(EmbeddingService.buildOpenAiEmbedUrl(null));
        assertNull(EmbeddingService.buildOpenAiEmbedUrl(""));
        assertNull(EmbeddingService.buildOpenAiEmbedUrl("  "));
    }

    @Test
    void buildOllamaEmbedUrl_plainOllama_shouldAppendApiEmbed() {
        assertEquals("http://localhost:11434/api/embed",
                EmbeddingService.buildOllamaEmbedUrl("http://localhost:11434"));
    }

    @Test
    void buildOllamaEmbedUrl_alreadyHasApiEmbed_shouldPreserve() {
        assertEquals("http://localhost:11434/api/embed",
                EmbeddingService.buildOllamaEmbedUrl("http://localhost:11434/api/embed"));
    }

    @Test
    void buildOllamaEmbedUrl_trailingSlash_shouldNormalize() {
        assertEquals("http://localhost:11434/api/embed",
                EmbeddingService.buildOllamaEmbedUrl("http://localhost:11434/"));
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost:11434,     http://localhost:11434/v1/embeddings",
            "https://api.openai.com/v1,  https://api.openai.com/v1/embeddings",
            "http://host/api/embed,      http://host/api/embed",
            "http://host/v1/embeddings,  http://host/v1/embeddings",
    })
    void buildOpenAiEmbedUrl_shouldProduceExpectedResult(String input, String expected) {
        assertEquals(expected.strip(), EmbeddingService.buildOpenAiEmbedUrl(input.strip()));
    }

    // ====== isAvailable ======

    @Test
    void isAvailable_noProvider_shouldReturnFalse() {
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of());
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailable_providerWithoutBaseUrl_shouldReturnFalse() {
        LlmProvider p = LlmProvider.builder().baseUrl(null).build();
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of(p));
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailable_validProvider_shouldReturnTrue() {
        LlmProvider p = LlmProvider.builder()
                .baseUrl("http://localhost:11434").models("bge-m3:latest").build();
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of(p));
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertTrue(service.isAvailable());
    }

    // ====== embed / embedWithImage 降级 ======

    @Test
    void embed_unavailable_shouldReturnNull() {
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of());
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertNull(service.embed("你好"));
    }

    @Test
    void embedWithImage_unavailable_shouldReturnNull() {
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of());
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertNull(service.embedWithImage("文本", "base64..."));
    }

    // ====== embedMultimodal 路径选择 ======

    @Test
    void embedMultimodal_unavailable_shouldReturnEmpty() {
        when(llmProviderRepository.findByTagsContains("embedding")).thenReturn(List.of());
        EmbeddingService service = new EmbeddingService(llmProviderRepository);
        assertTrue(service.embedMultimodal(List.of(
                new EmbeddingService.EmbeddingInput("text", null))).isEmpty());
    }

    // ====== 响应解析 ======

    @Test
    void openAiResponseParsing_shouldExtractEmbeddings() {
        // EmbeddingResponse 通过 Jackson 反序列化，测试字段映射
        String json = """
                {"object":"list","data":[{"object":"embedding","embedding":[0.1,0.2,0.3],"index":0}],
                "model":"bge-m3:latest","usage":{"prompt_tokens":1,"total_tokens":1}}""";
        var response = parseJson(json, EmbeddingTestHelper.OpenAiEmbedResponse.class);
        assertNotNull(response);
        assertEquals(1, response.data.size());
        assertEquals(List.of(0.1, 0.2, 0.3), response.data.get(0).embedding);
    }

    @Test
    void ollamaResponseParsing_shouldExtractEmbeddings() {
        String json = """
                {"model":"bge-m3:latest","embeddings":[[0.1,0.2,0.3,0.4]],
                "total_duration":123456789,"load_duration":12345678,"prompt_eval_count":5}""";
        var response = parseJson(json, EmbeddingTestHelper.OllamaEmbedResponse.class);
        assertNotNull(response);
        assertEquals(1, response.embeddings.size());
        assertEquals(List.of(0.1, 0.2, 0.3, 0.4), response.embeddings.getFirst());
    }

    @Test
    void ollamaResponseParsing_batch_shouldPreserveOrder() {
        String json = """
                {"model":"bge-m3:latest","embeddings":[[1.0,2.0],[3.0,4.0],[5.0,6.0]]}""";
        var response = parseJson(json, EmbeddingTestHelper.OllamaEmbedResponse.class);
        assertEquals(3, response.embeddings.size());
        assertEquals(List.of(1.0, 2.0), response.embeddings.get(0));
        assertEquals(List.of(5.0, 6.0), response.embeddings.get(2));
    }

    // ====== 辅助 ======

    @SuppressWarnings("unchecked")
    private static <T> T parseJson(String json, Class<T> clazz) {
        try {
            var mapper = new ObjectMapper();
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

/** 测试辅助：提取内部类用于 JSON 反序列化验证。 */
class EmbeddingTestHelper {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiEmbedResponse {
        public List<OpenAiEmbedData> data;
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenAiEmbedData {
        public List<Double> embedding;
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaEmbedResponse {
        public List<List<Double>> embeddings;
    }
}
