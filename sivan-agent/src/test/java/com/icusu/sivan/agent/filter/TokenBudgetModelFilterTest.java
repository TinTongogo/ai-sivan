package com.icusu.sivan.agent.filter;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBudgetModelFilterTest {

    @Mock private Model model;

    private TokenBudgetModelFilter filter;
    private ModelParams defaultParams;

    private Model.ModelResponse mockResponse;

    @BeforeEach
    void setUp() {
        filter = new TokenBudgetModelFilter(100);
        defaultParams = ModelParams.defaults();
        mockResponse = mock(Model.ModelResponse.class);
    }

    @Test
    void doFilter_shouldProceed_whenUnderBudget() {
        when(model.chat(any(), any())).thenReturn(Mono.just(mockResponse));

        List<Msg> msgs = List.of(
                Msg.of(Role.SYSTEM, "你是一个助手"),
                Msg.of(Role.USER, "你好"),
                Msg.of(Role.ASSISTANT, "你好！有什么可以帮助？")
        );
        filter.doFilter(model, msgs, defaultParams).block();

        verify(model).chat(argThat(list -> list.size() == 3), any());
    }

    @Test
    void doFilter_shouldProceed_whenFewMessages() {
        when(model.chat(any(), any())).thenReturn(Mono.just(mockResponse));

        List<Msg> msgs = List.of(Msg.of(Role.SYSTEM, "system"), Msg.of(Role.USER, "hi"));
        filter.doFilter(model, msgs, defaultParams).block();
        verify(model).chat(argThat(list -> list.size() == 2), any());
    }

    @Test
    void doFilter_shouldTruncate_whenOverBudget() {
        when(model.chat(any(), any())).thenReturn(Mono.just(mockResponse));

        // 每条消息 200 字符 → 估算 50 tokens，总共 6 条 → 300 tokens > 100 budget
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, "你是一个有用的助手。".repeat(40))); // ~200 chars
        for (int i = 0; i < 5; i++) {
            msgs.add(Msg.of(Role.USER, "这是一条用户消息，包含一些额外内容。".repeat(20)));
        }

        filter.doFilter(model, msgs, defaultParams).block();

        verify(model).chat(argThat(list -> list.size() == 4), any());
    }

    @Test
    void doFilter_shouldNotTruncate_whenParamsMaxTokensNull() {
        when(model.chat(any(), any())).thenReturn(Mono.just(mockResponse));

        TokenBudgetModelFilter filterWithBudget = new TokenBudgetModelFilter(Integer.MAX_VALUE);
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, "system"));
        for (int i = 0; i < 10; i++) {
            msgs.add(Msg.of(Role.USER, "msg" + i));
        }

        filterWithBudget.doFilter(model, msgs, defaultParams).block();
        verify(model).chat(argThat(list -> list.size() == 11), any());
    }

    @Test
    void doFilter_shouldUseParamsMaxTokens_whenProvided() {
        when(model.chat(any(), any())).thenReturn(Mono.just(mockResponse));

        ModelParams paramsWithLimit = ModelParams.defaults().withMaxTokens(30);
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.of(Role.SYSTEM, "sys"));
        for (int i = 0; i < 10; i++) {
            msgs.add(Msg.of(Role.USER, "这是一条用户消息。这里是一些额外的文本来增加长度。"));
        }

        filter.doFilter(model, msgs, paramsWithLimit).block();

        verify(model).chat(argThat(list -> list.size() < 11), any());
    }

    @Test
    void constructor_shouldSetDefaultBudget() {
        TokenBudgetModelFilter defaultFilter = new TokenBudgetModelFilter();
        assertNotNull(defaultFilter);
    }
}
