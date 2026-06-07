package com.icusu.sivan.web.conversation.service;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.model.ModelCapabilityRegistry;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.routing.RoutingDecisionRecorder;
import com.icusu.sivan.agent.tool.MatchedTools;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolRegistryImpl;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.common.enums.MessageStatus;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.memory.flashback.FlashbackScanner;
import com.icusu.sivan.domain.context.ContextSegment;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.tool.IToolMatchLogRepository;
import com.icusu.sivan.domain.context.Epoch;
import com.icusu.sivan.domain.conversation.*;
import com.icusu.sivan.domain.pipeline.IPipelineStepRepository;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import com.icusu.sivan.infra.shared.sse.StreamManager;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.orch.executor.SquadOrchestrator;
import com.icusu.sivan.web.conversation.dto.*;
import com.icusu.sivan.web.conversation.service.compress.ConversationCompressor;
import com.icusu.sivan.web.conversation.service.tree.ContextBuilder;
import com.icusu.sivan.web.conversation.service.tree.ContextCache;
import com.icusu.sivan.web.conversation.service.tree.ContextResult;
import com.icusu.sivan.web.conversation.service.tree.ConversationTree;
import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import com.icusu.sivan.web.agent.service.GroupService;
import com.icusu.sivan.web.knowledge.service.RagContextBuilder;
import com.icusu.sivan.web.routing.service.StrategyPerformanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对话服务测试
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private IConversationRepository conversationRepository;
    @Mock
    private IMessageRepository messageRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ToolRegistryImpl toolRegistry;
    @Mock
    private FileStoragePort fileStorageService;
    @Mock
    private RagContextBuilder ragContextBuilder;
    @Mock
    private StreamingMessageEngine streamingEngine;
    @Mock
    private SquadOrchestrator squadOrchestrator;
    @Mock
    private HistoryCompressor historyCompressor;
    @Mock
    private RoutingDecisionRecorder routingDecisionRecorder;
    @Mock
    private ToolResolver toolAutoResolver;
    @Mock
    private ToolEnricher toolEnricher;
    @Mock
    private ConversationTree conversationTree;
    @Mock
    private ContextBuilder contextBuilder;
    @Mock
    private ContextCache contextCache;
    @Mock
    private McpConnectionManager mcpConnectionManager;
    @Mock
    private IPipelineStepRepository pipelineStepRepository;
    @Mock
    private StrategyPerformanceService performanceService;
    @Mock
    private IAccountRepository accountRepository;
    @Mock
    private IToolMatchLogRepository toolMatchLogRepository;
    @Mock
    private ConversationCompressor conversationCompressor;
    @Mock
    private GroupService groupService;
    @Mock
    private FlashbackScanner flashbackScanner;
    @Mock
    private ModelCapabilityRegistry modelCapabilityRegistry;
    @Mock
    private IUserProfileRepository userProfileRepository;
    @Mock
    private ISkillRepository skillRepository;
    @Mock
    private IEmbeddingService embeddingService;

    @Mock
    private IGoalRepository goalRepository;

    @Mock
    private DocumentTextExtractor documentTextExtractor;

    private final StreamManager streamManager = new StreamManager();
    private ConversationService conversationService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
                streamManager, eventPublisher, modelRouter, fileStorageService, ragContextBuilder,
                squadOrchestrator, historyCompressor, routingDecisionRecorder, streamingEngine, toolAutoResolver,
                contextBuilder, contextCache, pipelineStepRepository, performanceService, accountRepository,
                mcpConnectionManager, toolEnricher, toolMatchLogRepository, skillRepository, toolRegistry, groupService,
                flashbackScanner, modelCapabilityRegistry, userProfileRepository, documentTextExtractor, embeddingService, goalRepository, conversationCompressor);
        // 默认 classify/resolveIntent 返回 CHAT，使现有测试走正常 LLM 路径
        // 同时匹配 5 参数（resolveIntent 无上下文）和 6 参数（resolveIntent 带上下文）变体
        lenient().when(squadOrchestrator.resolveIntent(anyString(), any(), any(), any(), any())).thenReturn(Mono.just(Intent.CHAT));
        lenient().when(squadOrchestrator.resolveIntent(anyString(), any(), any(), any(), any(), any())).thenReturn(Mono.just(Intent.CHAT));
        lenient().when(routingDecisionRecorder.record(any())).thenReturn(UUID.randomUUID());
        lenient().when(conversationCompressor.compress(anyList(), anyInt(), anyString()))
                .thenReturn(Mono.just(new CompressResult("", List.of(), List.of(), null, false)));

        // 默认工具匹配返回空
        lenient().when(toolAutoResolver.resolveForChat(anyString(), anyString(), any()))
                .thenReturn(MatchedTools.empty());

        // 默认编排返回完成事件（各测试可按需覆盖）
        lenient().when(squadOrchestrator.orchestrateStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .thenReturn(Flux.just(OrchestrationEvent.complete(Map.of(
                        "type", "chat", "content", "你好", "thinking", ""))));
    }

    /**
     * 创建对话成功。
     */
    @Test
    void create_shouldSucceed() {
        UUID projectId = UUID.randomUUID();
        ProjectEntity project = ProjectEntity.builder()
                .projectId(projectId).accountId(accountId).name("测试项目").build();
        when(groupService.findOwned(accountId, projectId)).thenReturn(project);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("测试对话");
        request.setProjectId(projectId);

        ConversationResponse response = conversationService.create(accountId, request);

        assertEquals("测试对话", response.getTitle());
        assertEquals(0, response.getMessageCount());
        verify(conversationRepository).save(any(Conversation.class));
    }

    /**
     * 使用默认标题创建对话。
     */
    @Test
    void create_shouldUseDefaultTitle() {
        UUID projectId = UUID.randomUUID();
        ProjectEntity project = ProjectEntity.builder()
                .projectId(projectId).accountId(accountId).name("测试项目").build();
        when(groupService.findOwned(accountId, projectId)).thenReturn(project);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setProjectId(projectId);

        ConversationResponse response = conversationService.create(accountId, request);

        assertEquals("新对话", response.getTitle());
    }

    /**
     * 根据 ID 获取对话。
     */
    @Test
    void getById_shouldReturnConversation() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).title("我的对话").build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));

        ConversationResponse response = conversationService.getById(accountId, convId);

        assertEquals("我的对话", response.getTitle());
    }

    /**
     * 获取非本人对话时应抛出异常。
     */
    @Test
    void getById_shouldThrowWhenNotOwned() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(UUID.randomUUID()).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));

        assertThrows(DomainException.class, () -> conversationService.getById(accountId, convId));
    }

    /**
     * 列出所有对话。
     */
    @Test
    void list_shouldReturnAllWhenNoProject() {
        Conversation conv = Conversation.builder()
                .conversationId(UUID.randomUUID()).accountId(accountId).build();

        when(conversationRepository.findAllByAccount(accountId)).thenReturn(List.of(conv));

        List<ConversationResponse> list = conversationService.list(accountId, null);

        assertEquals(1, list.size());
        verify(conversationRepository).findAllByAccount(accountId);
    }

    /**
     * 按项目过滤对话列表。
     */
    @Test
    void list_shouldFilterByProject() {
        UUID projectId = UUID.randomUUID();
        when(conversationRepository.findAllByAccountAndProject(accountId, projectId))
                .thenReturn(List.of());

        List<ConversationResponse> list = conversationService.list(accountId, projectId);

        assertTrue(list.isEmpty());
        verify(conversationRepository).findAllByAccountAndProject(accountId, projectId);
    }

    /**
     * 更新对话标题。
     */
    @Test
    void update_shouldModifyTitle() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).title("旧标题").build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));

        UpdateConversationRequest request = new UpdateConversationRequest();
        request.setTitle("新标题");

        ConversationResponse response = conversationService.update(accountId, convId, request);

        assertEquals("新标题", response.getTitle());
        verify(conversationRepository).update(conv);
    }

    /**
     * 删除对话及关联消息。
     */
    @Test
    void delete_shouldRemoveConversationAndMessages() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));

        conversationService.delete(accountId, convId);

        verify(messageRepository).deleteByConversationId(convId);
        verify(conversationRepository).delete(convId);
    }

    /**
     * 发送消息创建用户消息。
     */
    @Test
    void sendMessage_shouldCreateUserMessage() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).messageCount(0).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setMessageId(UUID.randomUUID());
            return message;
        });

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("你好");

        MessageResponse response = conversationService.sendMessage(accountId, convId, request);

        assertEquals("user", response.getRole());
        assertEquals("你好", response.getContent());
        verify(conversationRepository).update(conv);
    }

    /**
     * 获取对话消息列表。
     */
    @Test
    void getMessages_shouldReturnList() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));

        Message message = Message.builder()
                .messageId(UUID.randomUUID()).conversationId(convId)
                .role("user").content("你好").status(MessageStatus.COMPLETED)
                .sortOrder(1).build();

        when(messageRepository.findLatestByConversationId(convId, 50)).thenReturn(List.of(message));

        MessagePageResponse page = conversationService.getMessages(accountId, convId);
        List<MessageResponse> messages = page.getMessages();

        assertEquals(1, messages.size());
        assertEquals("你好", messages.getFirst().getContent());
    }

    /**
     * 评价消息。
     */
    @Test
    void rateMessage_shouldUpdateRating() {
        UUID msgId = UUID.randomUUID();
        Message message = Message.builder().messageId(msgId).accountId(accountId).build();

        when(messageRepository.findById(msgId)).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenReturn(message);

        MessageResponse response = conversationService.rateMessage(accountId, msgId, "like");

        assertEquals("like", response.getRating());
    }

    /**
     * 流式发送消息 — 验证编排链路完整执行。
     */
    @Test
    void streamMessage_shouldSaveUserAndReturnLLMFlux() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).projectId(null).messageCount(0).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));
        when(messageRepository.findLatestByConversationId(convId, 500)).thenReturn(List.of());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getMessageId() == null) message.setMessageId(UUID.randomUUID());
            return message;
        });
        when(historyCompressor.compressStream(any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(new CompressResult("", List.of(), List.of(), null, false)));
        when(contextBuilder.buildEpochs(any(), any(), anyInt(), anyList()))
                .thenReturn(ContextResult.empty());
        when(contextCache.apply(any(), any()))
                .thenReturn(ContextResult.empty());

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("测试消息");

        // 验证流式消息不抛出异常
        List<String> tokens = conversationService.streamMessage(accountId, convId, request).collectList().block();

        assertNotNull(tokens);
        verify(streamingEngine).register(any(UUID.class), any());
    }

    /**
     * 评价不存在的消息时应抛出异常。
     */
    @Test
    void rateMessage_shouldThrowWhenNotFound() {
        UUID msgId = UUID.randomUUID();
        when(messageRepository.findById(msgId)).thenReturn(Optional.empty());

        assertThrows(DomainException.class,
                () -> conversationService.rateMessage(accountId, msgId, "like"));
    }

    /**
     * CHAT 路径：验证历史压缩 → Epoch 构建 → 缓存 → LLM 消息注入的完整链路。
     */
    @Test
    void streamMessage_withCompression_shouldUseEpochAndCache() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).projectId(null).messageCount(0).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));
        when(messageRepository.findLatestByConversationId(convId, 500)).thenReturn(List.of());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getMessageId() == null) message.setMessageId(UUID.randomUUID());
            return message;
        });
        lenient().when(ragContextBuilder.build(anyString(), any(), any())).thenReturn("ragContext");

        // Mock 历史压缩 → Epoch 构建 → 缓存
        CompressResult compressResult = new CompressResult("测试摘要",
                List.of(), List.of(UUID.randomUUID()), null, false);
        when(conversationCompressor.compress(anyList(), anyInt(), anyString()))
                .thenReturn(Mono.just(compressResult));

        ContextResult epochResult = new ContextResult(List.of(
                new ContextSegment(Epoch.EPOCH_3_HISTORY, "历史摘要", false),
                new ContextSegment(Epoch.EPOCH_4_ACTIVE, "活跃消息", false)
        ));
        when(contextBuilder.buildEpochs(any(), any(), anyInt(), anyList())).thenReturn(epochResult);
        when(contextCache.apply(any(), any())).thenReturn(epochResult);

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("测试消息");

        List<String> tokens = conversationService.streamMessage(accountId, convId, request).collectList().block();
        assertNotNull(tokens);

        // 验证完整链路调用（主流程 + 异步后台压缩）
        verify(conversationCompressor).compress(anyList(), anyInt(), anyString());
        verify(contextBuilder).buildEpochs(any(), any(), anyInt(), anyList());
        verify(contextCache).apply(any(), any());
        verify(streamingEngine).register(any(UUID.class), any());
    }

    /**
     * CHAT 路径：压缩失败时应降级跳过 Epoch/Cache，仍正常启动流式引擎。
     */
    @Test
    void streamMessage_whenCompressionFails_shouldFallbackGracefully() {
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder()
                .conversationId(convId).accountId(accountId).projectId(null).messageCount(0).build();

        when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));
        lenient().when(messageRepository.findLatestByConversationId(convId, 500)).thenReturn(List.of());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getMessageId() == null) message.setMessageId(UUID.randomUUID());
            return message;
        });

        // 历史压缩首次抛出异常，onErrorResume 降级重试后成功
        when(conversationCompressor.compress(anyList(), anyInt(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("压缩服务不可用")))
                .thenReturn(Mono.just(new CompressResult("", List.of(), List.of(), null, false)));

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("测试消息");

        List<String> tokens = conversationService.streamMessage(accountId, convId, request).collectList().block();
        assertNotNull(tokens);

        // 降级后编排流继续执行（onErrorResume 捕获异常，空 CompressResult 继续后续流程）
        verify(streamingEngine).register(any(UUID.class), any());
    }
}
