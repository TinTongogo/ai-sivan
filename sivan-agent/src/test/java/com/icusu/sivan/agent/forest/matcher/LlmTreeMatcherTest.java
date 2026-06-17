package com.icusu.sivan.agent.forest.matcher;

import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.node.InnerGoalNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmTreeMatcherTest {

    private LlmTreeMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new LlmTreeMatcher(null, null);
    }

    @Test
    void parseJson_sequential() {
        String json = "{\"type\":\"sequential\",\"reasoning\":\"分两步\",\"steps\":[{\"content\":\"分析需求\"},{\"content\":\"实现代码\"}]}";
        var node = matcher.parseTree(json, "test");
        assertNotNull(node);
        assertEquals("inner_goal", node.nodeType());
        assertEquals(2, node.children().size());
        ContentNode cn = (ContentNode) node.children().getFirst();
        assertEquals("分析需求", cn.content());
    }

    @Test
    void parseJson_single() {
        String json = "{\"type\":\"single\",\"reasoning\":\"简单任务\",\"steps\":[{\"content\":\"写一个 hello.py\"}]}";
        var node = matcher.parseTree(json, "test");
        assertNotNull(node);
        assertEquals("task", node.nodeType());
    }

    @Test
    void parseJson_parallel() {
        String json = "{\"type\":\"parallel\",\"reasoning\":\"同时进行\",\"steps\":[{\"content\":\"任务A\"},{\"content\":\"任务B\"}]}";
        var node = matcher.parseTree(json, "test");
        var innerGoal = (InnerGoalNode) node;
        assertEquals(com.icusu.sivan.common.Mode.PARALLEL, innerGoal.mode());
    }

    @Test
    void parseJson_conditional() {
        String json = "{\"type\":\"conditional\",\"reasoning\":\"条件判断\",\"steps\":[{\"content\":\"检查结果\"},{\"content\":\"成功部署\"},{\"content\":\"失败回滚\"}]}";
        var node = matcher.parseTree(json, "test");
        var innerGoal = (InnerGoalNode) node;
        assertEquals(com.icusu.sivan.common.Mode.CONDITIONAL, innerGoal.mode());
    }

    @Test
    void parseJson_hierarchical() {
        String json = "{\"type\":\"hierarchical\",\"reasoning\":\"分层执行\",\"steps\":[{\"content\":\"总体规划\"},{\"content\":\"子任务A\"},{\"content\":\"汇总结果\"}]}";
        var node = matcher.parseTree(json, "test");
        var innerGoal = (InnerGoalNode) node;
        assertEquals(com.icusu.sivan.common.Mode.HIERARCHICAL, innerGoal.mode());
    }

    @Test
    void parseJson_consensus() {
        String json = "{\"type\":\"consensus\",\"reasoning\":\"多视角\",\"steps\":[{\"content\":\"安全分析\"},{\"content\":\"性能分析\"},{\"content\":\"综合分析\"}]}";
        var node = matcher.parseTree(json, "test");
        var innerGoal = (InnerGoalNode) node;
        assertEquals(com.icusu.sivan.common.Mode.CONSENSUS, innerGoal.mode());
    }

    @Test
    void parseJson_markdownBlock() {
        String json = "```json\n{\"type\":\"single\",\"steps\":[{\"content\":\"测试\"}]}\n```";
        var node = matcher.parseTree(json, "test");
        assertNotNull(node);
        assertEquals("task", node.nodeType());
    }

    @Test
    void parseJson_invalidJson_fallbackToTaskNode() {
        String badJson = "这不是 JSON";
        var node = matcher.parseTree(badJson, "原始输入");
        assertEquals("task", node.nodeType());
        assertEquals("原始输入", ((ContentNode) node).content());
    }

    @Test
    void parseJson_emptySteps_fallbackToTaskNode() {
        String json = "{\"type\":\"sequential\",\"steps\":[]}";
        var node = matcher.parseTree(json, "兜底内容");
        assertEquals("task", node.nodeType());
        assertEquals("兜底内容", ((ContentNode) node).content());
    }

    @Test
    void parseJson_noSteps_fallbackToTaskNode() {
        String json = "{\"type\":\"single\"}";
        var node = matcher.parseTree(json, "兜底");
        assertEquals("task", node.nodeType());
    }

    @Test
    void extractJson_markdownBlock() {
        String text = "```json\n{\"type\":\"single\"}\n```";
        String result = LlmTreeMatcher.extractJson(text);
        assertEquals("{\"type\":\"single\"}", result);
    }

    @Test
    void extractJson_rawJson() {
        String text = "{\"type\":\"single\"}";
        String result = LlmTreeMatcher.extractJson(text);
        assertEquals("{\"type\":\"single\"}", result);
    }

    @Test
    void extractJson_noJson() {
        String text = "这是一个普通文本回复";
        assertNull(LlmTreeMatcher.extractJson(text));
    }
}
