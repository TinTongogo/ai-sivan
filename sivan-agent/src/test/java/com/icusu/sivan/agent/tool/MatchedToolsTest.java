package com.icusu.sivan.agent.tool;

import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.domain.tool.ToolMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchedToolsTest {

    @Test
    void empty_返回空结果() {
        MatchedTools mt = MatchedTools.empty();
        assertTrue(mt.isEmpty());
        assertTrue(mt.metas().isEmpty());
        assertTrue(mt.schemas().isEmpty());
        assertTrue(mt.toolScores().isEmpty());
        assertTrue(mt.toolServerIds().isEmpty());
    }

    @Test
    void isEmpty_元数据为null或空时返回true() {
        assertTrue(new MatchedTools(null, null).isEmpty());
        assertTrue(new MatchedTools(List.of(), List.of()).isEmpty());
        assertFalse(new MatchedTools(List.of(new ToolMeta()), List.of()).isEmpty());
    }

    @Test
    void 紧凑构造器_默认空映射() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("test-tool");
        MatchedTools mt = new MatchedTools(List.of(meta), List.of(new ToolSpec("test-tool", "desc", Map.of())));
        assertFalse(mt.isEmpty());
        assertTrue(mt.toolScores().isEmpty());
        assertEquals(0.0, mt.threshold());
    }

    @Test
    void 完整构造器_保留所有字段() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("full-tool");
        meta.setServerId("srv-1");
        ToolSpec spec = new ToolSpec("full-tool", "测试", Map.of("type", "object"));
        Map<String, Double> scores = Map.of("full-tool", 0.95);
        Map<String, String> serverIds = Map.of("full-tool", "srv-1");

        MatchedTools mt = new MatchedTools(List.of(meta), List.of(spec), scores, 0.5, serverIds);

        assertEquals(1, mt.metas().size());
        assertEquals(1, mt.schemas().size());
        assertEquals(0.95, mt.toolScores().get("full-tool"));
        assertEquals(0.5, mt.threshold());
        assertEquals("srv-1", mt.toolServerIds().get("full-tool"));
    }
}
