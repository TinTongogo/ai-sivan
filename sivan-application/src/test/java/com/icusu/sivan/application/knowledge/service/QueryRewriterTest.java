package com.icusu.sivan.application.knowledge.service;

import com.icusu.sivan.application.knowledge.QueryRewriter;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.model.Model.ModelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryRewriter 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryRewriterTest {

    @Mock
    private ModelRouter modelRouter;
    @Mock
    private Model mockModel;

    private QueryRewriter rewriter;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rewriter = new QueryRewriter(modelRouter);
        when(modelRouter.getDefaultModel(accountId)).thenReturn(mockModel);
    }

    @Test
    void rewrite_shouldReturnMultipleVariants() {
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT,
                                "如何修复登录认证失败\n登录认证错误解决方法"),
                        null)));

        List<String> result = rewriter.rewrite("登录认证失败怎么修", accountId).block();
        assertNotNull(result);
        assertTrue(result.size() >= 2, "应返回至少2个变体");
    }

    @Test
    void rewrite_shouldIncludeOriginal_whenNotPresentInVariants() {
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT,
                                "python list comprehension\n列表推导式语法"),
                        null)));

        List<String> result = rewriter.rewrite("Python 列表推导式", accountId).block();
        assertNotNull(result);
        assertTrue(result.contains("Python 列表推导式"), "原始查询应在结果中");
        assertTrue(result.size() >= 2);
    }

    @Test
    void rewrite_shouldHandleBlankVariants() {
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, ""), null)));

        List<String> result = rewriter.rewrite("test query", accountId).block();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("test query", result.get(0));
    }

    @Test
    void rewrite_shouldHandleEmptyInput() {
        List<String> result = rewriter.rewrite("", accountId).block();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("", result.get(0));
    }

    @Test
    void rewrite_shouldHandleNullInput() {
        List<String> result = rewriter.rewrite(null, accountId).block();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNull(result.get(0));
    }

    @Test
    void rewrite_shouldRemoveNumberPrefixes() {
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT,
                                "1. 变体一\n2. 变体二\n3. 变体三"),
                        null)));

        List<String> result = rewriter.rewrite("查询", accountId).block();
        assertNotNull(result);
        assertTrue(result.stream().noneMatch(v -> v.matches("^\\d+[.、].*")),
                "变体不应包含数字编号前缀");
    }

    @Test
    void rewrite_shouldFallbackOnError() {
        when(mockModel.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.error(new RuntimeException("LLM 调用失败")));

        List<String> result = rewriter.rewrite("测试查询", accountId).block();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("测试查询", result.get(0));
    }
}
