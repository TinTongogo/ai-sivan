package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.service.TokenUsageRecorder;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.core.agent.ExecutionStrategy;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.Disposable;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamingMessageEngineTest {

    @Mock private ModelRouter modelRouter;
    @Mock private ToolProvider toolProvider;
    @Mock private ExecutionStrategy executionStrategy;
    @Mock private StreamManager streamManager;
    @Mock private IMessageRepository messageRepository;
    @Mock private IConversationRepository conversationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TokenUsageRecorder tokenUsageRecorder;

    private StreamingMessageEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StreamingMessageEngine(modelRouter, toolProvider, executionStrategy,
                streamManager, messageRepository, conversationRepository,
                eventPublisher, tokenUsageRecorder);
    }

    @Test
    void isActive_未注册返回false() {
        assertFalse(engine.isActive(UUID.randomUUID()));
    }

    @Test
    void register_之后isActive返回true() {
        UUID msgId = UUID.randomUUID();
        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);

        engine.register(msgId, mockDisposable);
        assertTrue(engine.isActive(msgId));
    }

    @Test
    void unregister_之后isActive返回false() {
        UUID msgId = UUID.randomUUID();
        Disposable mockDisposable = mock(Disposable.class);
        engine.register(msgId, mockDisposable);

        engine.unregister(msgId);
        assertFalse(engine.isActive(msgId));
    }

    @Test
    void cancel_清理订阅和流() {
        UUID msgId = UUID.randomUUID();
        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);

        engine.register(msgId, mockDisposable);
        engine.cancel(msgId);

        verify(streamManager).complete(msgId);
        verify(streamManager).remove(msgId);
        verify(mockDisposable).dispose();
        assertFalse(engine.isActive(msgId));
    }

    @Test
    void cancel_消息RUNNING状态变FAILED() {
        UUID msgId = UUID.randomUUID();
        Message msg = new Message();
        msg.setMessageId(msgId);
        msg.setStatus(MessageStatus.RUNNING);

        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);
        when(messageRepository.findById(msgId)).thenReturn(Optional.of(msg));

        engine.register(msgId, mockDisposable);
        engine.cancel(msgId);

        assertEquals(MessageStatus.FAILED, msg.getStatus());
        verify(messageRepository).save(msg);
    }

    @Test
    void cancel_消息内容为空设置默认取消文本() {
        UUID msgId = UUID.randomUUID();
        Message msg = new Message();
        msg.setMessageId(msgId);
        msg.setStatus(MessageStatus.RUNNING);

        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);
        when(messageRepository.findById(msgId)).thenReturn(Optional.of(msg));

        engine.register(msgId, mockDisposable);
        engine.cancel(msgId);

        assertEquals("已取消", msg.getContent());
    }

    @Test
    void cancel_已有内容不覆盖() {
        UUID msgId = UUID.randomUUID();
        Message msg = new Message();
        msg.setMessageId(msgId);
        msg.setStatus(MessageStatus.RUNNING);
        msg.setContent("已有部分内容");

        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);
        when(messageRepository.findById(msgId)).thenReturn(Optional.of(msg));

        engine.register(msgId, mockDisposable);
        engine.cancel(msgId);

        assertEquals("已有部分内容", msg.getContent());
    }

    @Test
    void cancel_已完结消息不重复标记() {
        UUID msgId = UUID.randomUUID();
        Message msg = new Message();
        msg.setMessageId(msgId);
        msg.setStatus(MessageStatus.COMPLETED);
        msg.setContent("已完成");

        Disposable mockDisposable = mock(Disposable.class);
        when(mockDisposable.isDisposed()).thenReturn(false);
        when(messageRepository.findById(msgId)).thenReturn(Optional.of(msg));

        engine.register(msgId, mockDisposable);
        engine.cancel(msgId);

        assertEquals(MessageStatus.COMPLETED, msg.getStatus()); // 不变
        verify(messageRepository, never()).save(msg); // 不保存
    }
}
