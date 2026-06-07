package com.icusu.sivan.infra.knowledge;

import com.icusu.sivan.domain.model.ILlmProviderRepository;
import com.icusu.sivan.domain.model.LlmProvider;
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
class RerankerServiceTest {

    @Mock private ILlmProviderRepository llmProviderRepository;

    // ====== URL 规范化 ======

    @ParameterizedTest
    @CsvSource({
            "http://localhost:11434,                http://localhost:11434/api/rerank",
            "http://localhost:11434/,               http://localhost:11434/api/rerank",
            "http://host:8080/v1/,                  http://host:8080/v1/api/rerank",
            "http://host:8080/v1/score,             http://host:8080/v1/score",
            "http://host:8080/api/rerank,           http://host:8080/api/rerank",
    })
    void normalizeRerankUrl_shouldProduceExpectedResult(String input, String expected) {
        assertEquals(expected, RerankerService.normalizeRerankUrl(input));
    }

    @Test
    void normalizeRerankUrl_nullOrBlank_shouldReturnInput() {
        assertNull(RerankerService.normalizeRerankUrl(null));
        assertEquals("", RerankerService.normalizeRerankUrl(""));
    }

    // ====== isAvailable ======

    @Test
    void isAvailable_noProvider_shouldReturnFalse() {
        when(llmProviderRepository.findByTagsContains("reranker")).thenReturn(List.of());
        RerankerService service = new RerankerService(llmProviderRepository);
        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailable_providerWithoutBaseUrl_shouldReturnFalse() {
        LlmProvider p = LlmProvider.builder().baseUrl(null).build();
        when(llmProviderRepository.findByTagsContains("reranker")).thenReturn(List.of(p));
        RerankerService service = new RerankerService(llmProviderRepository);
        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailable_validProvider_shouldReturnTrue() {
        LlmProvider p = LlmProvider.builder()
                .baseUrl("http://localhost:11434").models("bge-reranker:latest").build();
        when(llmProviderRepository.findByTagsContains("reranker")).thenReturn(List.of(p));
        RerankerService service = new RerankerService(llmProviderRepository);
        assertTrue(service.isAvailable());
    }

    // ====== rerank 降级 ======

    @Test
    void rerank_unavailable_shouldReturnEmpty() {
        when(llmProviderRepository.findByTagsContains("reranker")).thenReturn(List.of());
        RerankerService service = new RerankerService(llmProviderRepository);
        assertTrue(service.rerank("查询", List.of("文档1", "文档2")).isEmpty());
    }

    @Test
    void rerank_noModel_shouldReturnEmpty() {
        LlmProvider p = LlmProvider.builder()
                .baseUrl("http://localhost:11434").build();
        when(llmProviderRepository.findByTagsContains("reranker")).thenReturn(List.of(p));
        RerankerService service = new RerankerService(llmProviderRepository);
        assertTrue(service.rerank("查询", List.of("文档")).isEmpty());
    }

    // ====== 辅助方法测试（通过反射测试私有方法不方便，通过 TestUtil 间接验证） ======

    @Test
    void isVllmStyle_shouldDetectVllmUrls() {
        assertTrue(callIsVllmStyle("http://host/v1/chat/completions"));
        assertTrue(callIsVllmStyle("http://host/v1/"));
        assertFalse(callIsVllmStyle("http://host:11434"));
        assertFalse(callIsVllmStyle("http://host/api/rerank"));
    }

    @Test
    void isLocalUrl_shouldDetectLocalAddresses() {
        assertTrue(callIsLocalUrl("http://localhost:11434"));
        assertTrue(callIsLocalUrl("http://127.0.0.1:8080"));
        assertTrue(callIsLocalUrl("http://0.0.0.0:11434"));
        assertFalse(callIsLocalUrl("http://api.openai.com"));
        assertFalse(callIsLocalUrl(null));
    }

    @Test
    void isOpenaiCompatible_shouldDetectOpenaiProviders() {
        LlmProvider p = LlmProvider.builder().providerType("openai").build();
        assertTrue(callIsOpenaiCompatible(p, "http://api.openai.com"));
        LlmProvider p2 = LlmProvider.builder().providerType("ollama").build();
        assertFalse(callIsOpenaiCompatible(p2, "http://localhost:11434"));
        // URL 含 /v1/ 时即使 providerType 不是 openai 也返回 true
        assertTrue(callIsOpenaiCompatible(p2, "http://host/v1/"));
    }

    // ====== Prompt 构建 ======

    @Test
    void buildScoringPrompt_shouldContainQueryAndDocuments() {
        String prompt = callBuildScoringPrompt("测试查询", List.of("文档A", "文档B", "文档C"), 3);
        assertTrue(prompt.contains("测试查询"));
        assertTrue(prompt.contains("文档A"));
        assertTrue(prompt.contains("文档C"));
        assertTrue(prompt.contains("[分数0, 分数1, 分数2, ...]"));
    }

    @Test
    void buildScoringPrompt_shouldTruncateLongDocuments() {
        String longDoc = "a".repeat(1000);
        String prompt = callBuildScoringPrompt("q", List.of(longDoc), 1);
        assertTrue(prompt.length() < longDoc.length() + 200);
    }

    // ====== 响应解析 ======

    @Test
    void parseJsonScores_shouldHandleStandardJson() {
        List<Integer> result = callParseJsonScores("[9, 5, 7]", 3);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(0, result.get(0)); // 分数最高 9 → index 0
        assertEquals(2, result.get(1)); // 分数 7 → index 2
        assertEquals(1, result.get(2)); // 分数 5 → index 1
    }

    @Test
    void parseJsonScores_shouldHandleMarkdownBlock() {
        List<Integer> result = callParseJsonScores("```json\n[3, 8, 1]\n```", 3);
        assertNotNull(result);
        assertEquals(1, result.get(0)); // 分数 8 → index 1
    }

    @Test
    void parseJsonScores_shouldReturnNull_onInvalidJson() {
        assertNull(callParseJsonScores("不是JSON", 3));
    }

    @Test
    void parseLineScores_shouldParseLines() {
        String response = "9\n5\n7";
        List<Integer> result = callParseLineScores(response, 3);
        assertNotNull(result);
        assertEquals(0, result.get(0)); // 9 → index 0
        assertEquals(2, result.get(1)); // 7 → index 2
    }

    @Test
    void parseLineScores_shouldHandleExtraText() {
        String response = "文档1: 8分\n文档2: 6分\n文档3: 9分";
        List<Integer> result = callParseLineScores(response, 3);
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    void parseLineScores_shouldReturnNull_whenTooFewParsed() {
        assertNull(callParseLineScores("仅一个数字", 10));
    }

    // ====== 反射辅助：调用私有方法 ======

    private boolean callIsVllmStyle(String url) {
        try {
            var s = new RerankerService(llmProviderRepository);
            var m = RerankerService.class.getDeclaredMethod("isVllmStyle", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(s, url);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean callIsLocalUrl(String url) {
        try {
            var m = RerankerService.class.getDeclaredMethod("isLocalUrl", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, url);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean callIsOpenaiCompatible(LlmProvider provider, String url) {
        try {
            var m = RerankerService.class.getDeclaredMethod("isOpenaiCompatible", LlmProvider.class, String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, provider, url);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String callBuildScoringPrompt(String query, List<String> texts, int maxDocs) {
        try {
            var m = RerankerService.class.getDeclaredMethod("buildScoringPrompt", String.class, List.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(null, query, texts, maxDocs);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> callParseJsonScores(String response, int maxDocs) {
        try {
            RerankerService service = new RerankerService(llmProviderRepository);
            var m = RerankerService.class.getDeclaredMethod("parseJsonScores", String.class, int.class);
            m.setAccessible(true);
            return (List<Integer>) m.invoke(service, response, maxDocs);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> callParseLineScores(String response, int maxDocs) {
        try {
            RerankerService service = new RerankerService(llmProviderRepository);
            var m = RerankerService.class.getDeclaredMethod("parseLineScores", String.class, int.class);
            m.setAccessible(true);
            return (List<Integer>) m.invoke(service, response, maxDocs);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
