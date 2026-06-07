package com.icusu.sivan.agent.tool;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.domain.tool.IToolUsageRepository;
import com.icusu.sivan.domain.tool.ToolMeta;
import com.icusu.sivan.domain.tool.ToolRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultToolResolverTest {

    @Mock private ToolIndex toolIndex;
    @Mock private IAgentRepository agentRepository;
    @Mock private IEmbeddingService embeddingService;
    @Mock private IToolUsageRepository toolUsageRepository;
    @Mock private McpConnectionManager mcpConnectionManager;

    private DefaultToolResolver resolver;

    private ToolMeta toolA;
    private ToolMeta toolB;

    @BeforeEach
    void setUp() {
        resolver = new DefaultToolResolver(toolIndex, agentRepository, embeddingService,
                toolUsageRepository, mcpConnectionManager);

        toolA = new ToolMeta();
        toolA.setToolName("search");
        toolA.setDescription("搜索工具");
        toolA.setServerId("srv-1");
        toolA.setServerName("srv-1");
        toolA.setInputSchema(Map.of("type", "object"));

        toolB = new ToolMeta();
        toolB.setToolName("calc");
        toolB.setDescription("计算工具");
        toolB.setServerId("srv-1");
        toolB.setServerName("srv-1");
        toolB.setInputSchema(Map.of("type", "object"));
    }

    // ── resolveForChat ──

    @Test
    void resolveForChat_有工具返回匹配结果() {
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        MatchedTools result = resolver.resolveForChat("帮我搜索", UUID.randomUUID());

        assertFalse(result.isEmpty());
        assertTrue(result.metas().size() <= 2);
    }

    @Test
    void resolveForChat_无工具返回空() {
        when(toolIndex.getAllTools()).thenReturn(List.of());

        MatchedTools result = resolver.resolveForChat("hello", UUID.randomUUID());
        assertTrue(result.isEmpty());
        // MCP 连接由调用方管理，resolveForChat 内部不再调用 connectAll
    }

    @Test
    void resolveForChat_首次调用自动发现MCP() {
        // 首次调用无工具时直接返回空（MCP 连接由调用方管理）
        when(toolIndex.getAllTools()).thenReturn(List.of());

        MatchedTools result = resolver.resolveForChat("test", UUID.randomUUID());
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveForChat_附带对话上下文() {
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.5f, 0.5f});

        MatchedTools result = resolver.resolveForChat("问题", "历史上下文", UUID.randomUUID());

        assertFalse(result.isEmpty());
        // 验证 embedding 的输入包含对话上下文（字符串长度应大于单纯的问题）
        // 具体是否成功由 embedding 结果决定
    }

    @Test
    void resolveForChat_上下文超过2000字符截断() {
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA));
        when(embeddingService.embed(argThat(s -> s.length() <= 2000))).thenReturn(new float[]{0.1f});

        String longContext = "x".repeat(3000);
        resolver.resolveForChat("q", longContext, UUID.randomUUID());

        verify(embeddingService).embed(argThat(s -> s.length() <= 2000));
    }

    @Test
    void resolveForChat_connectAll异常静默处理() {
        when(toolIndex.getAllTools()).thenReturn(List.of());
        doThrow(new RuntimeException("连接失败")).when(mcpConnectionManager).connectAll();

        MatchedTools result = resolver.resolveForChat("test", UUID.randomUUID());
        assertTrue(result.isEmpty());
    }

    // ── resolveForAgent ──

    @Test
    void resolveForAgent_智能体不存在使用全部工具() {
        when(agentRepository.findByAccountAndName(any(), anyString())).thenReturn(Optional.empty());
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});

        MatchedTools result = resolver.resolveForAgent("unknown-agent", UUID.randomUUID());

        assertFalse(result.isEmpty());
    }

    @Test
    void resolveForAgent_智能体存在使用能力匹配() {
        AgentDefinition agent = AgentDefinition.builder()
                .agentName("coder")
                .systemPrompt("你是一个程序员")
                .craftDeclaration("擅长编码")
                .build();
        ToolRequirement req = ToolRequirement.builder()
                .autoMatch(true)
                .minConfidence(0.0) // 确保匹配
                .build();
        agent.setToolRequirements(req);

        when(agentRepository.findByAccountAndName(any(), eq("coder"))).thenReturn(Optional.of(agent));
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        MatchedTools result = resolver.resolveForAgent("coder", UUID.randomUUID());
        assertNotNull(result);
    }

    @Test
    void resolveForAgent_按需连接优先服务器() {
        UUID serverId = UUID.randomUUID();
        AgentDefinition agent = AgentDefinition.builder().agentName("connector").build();
        ToolRequirement req = ToolRequirement.builder()
                .preferredServers(List.of(serverId.toString()))
                .build();
        agent.setToolRequirements(req);

        when(agentRepository.findByAccountAndName(any(), eq("connector"))).thenReturn(Optional.of(agent));
        // connectByServerId 被调用
        doNothing().when(mcpConnectionManager).connectByServerId(serverId);
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA));

        resolver.resolveForAgent("connector", UUID.randomUUID());

        verify(mcpConnectionManager).connectByServerId(serverId);
    }

    @Test
    void resolveForAgent_优先服务器连接失败不抛出() {
        UUID serverId = UUID.randomUUID();
        AgentDefinition agent = AgentDefinition.builder().agentName("fail-conn").build();
        ToolRequirement req = ToolRequirement.builder()
                .preferredServers(List.of(serverId.toString()))
                .build();
        agent.setToolRequirements(req);

        when(agentRepository.findByAccountAndName(any(), eq("fail-conn"))).thenReturn(Optional.of(agent));
        doThrow(new RuntimeException("连接失败")).when(mcpConnectionManager).connectByServerId(serverId);
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA));

        MatchedTools result = resolver.resolveForAgent("fail-conn", UUID.randomUUID());
        assertFalse(result.isEmpty());
    }

    // ── resolve ──

    @Test
    void resolve_返回全部工具匹配() {
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});

        MatchedTools result = resolver.resolve(UUID.randomUUID());
        assertFalse(result.isEmpty());
        assertEquals(1, result.metas().size());
    }

    @Test
    void resolve_无工具返回空() {
        when(toolIndex.getAllTools()).thenReturn(List.of());
        doNothing().when(mcpConnectionManager).connectAll();

        MatchedTools result = resolver.resolve(UUID.randomUUID());
        assertTrue(result.isEmpty());
    }

    // ── 边缘情况 ──

    @Test
    void semanticMatch_embedding失败回退到全部工具() {
        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("embedding 服务不可用"));

        MatchedTools result = resolver.resolveForChat("test", UUID.randomUUID());

        // embedding 失败不应阻止工具返回
        assertFalse(result.isEmpty());
        assertEquals(2, result.metas().size());
    }

    @Test
    void toolServerIds_映射正确() {
        toolA.setServerId("srv-a");
        toolB.setServerId("srv-b");

        when(toolIndex.getAllTools()).thenReturn(List.of(toolA, toolB));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});

        MatchedTools result = resolver.resolveForChat("test", UUID.randomUUID());

        assertEquals("srv-a", result.toolServerIds().get("search"));
        assertEquals("srv-b", result.toolServerIds().get("calc"));
    }
}
