package com.icusu.sivan.infra.forest.matcher;

import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultTreeMatcher} 单元测试。
 */
class DefaultTreeMatcherTest {

    private final DefaultTreeMatcher matcher = new DefaultTreeMatcher();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void shortMessageReturnsTaskNode() {
        StepVerifier.create(matcher.match("你好", accountId))
                .assertNext(node -> {
                    assertInstanceOf(TaskNode.class, node);
                    assertEquals("你好", ((TaskNode) node).content());
                })
                .verifyComplete();
    }

    @Test
    void nullInputReturnsEmptyTaskNode() {
        StepVerifier.create(matcher.match(null, accountId))
                .assertNext(node -> {
                    assertInstanceOf(TaskNode.class, node);
                    assertEquals("", ((TaskNode) node).content());
                })
                .verifyComplete();
    }

    @Test
    void blankInputReturnsEmptyTaskNode() {
        StepVerifier.create(matcher.match("   ", accountId))
                .assertNext(node -> assertInstanceOf(TaskNode.class, node))
                .verifyComplete();
    }

    @Test
    void stepKeywordReturnsInnerGoalNode() {
        String input = "实现登录功能\n1. 设计数据库\n2. 编写接口\n3. 测试";
        StepVerifier.create(matcher.match(input, accountId))
                .assertNext(node -> {
                    assertInstanceOf(InnerGoalNode.class, node);
                    var goal = (InnerGoalNode) node;
                    assertEquals(3, goal.children().size());
                })
                .verifyComplete();
    }

    @Test
    void stepKeywordWithDashReturnsInnerGoalNode() {
        String input = "部署步骤\n- 拉取代码\n- 构建镜像\n- 重启服务";
        StepVerifier.create(matcher.match(input, accountId))
                .assertNext(node -> {
                    assertInstanceOf(InnerGoalNode.class, node);
                    assertEquals(3, ((InnerGoalNode) node).children().size());
                })
                .verifyComplete();
    }

    @Test
    void longTextWithoutStepsReturnsTaskNode() {
        String input = "这是一个很长的文本，大概超过二十个字符了，但是没有明确的步骤划分，所以应该返回一个单步任务节点";
        StepVerifier.create(matcher.match(input, accountId))
                .assertNext(node -> assertInstanceOf(TaskNode.class, node))
                .verifyComplete();
    }

    @Test
    void keywordWithSingleStepReturnsTaskNode() {
        // 第一步 关键词匹配，但无法拆分出步骤 → TaskNode
        String input = "第一步 完成这个任务，没有明确的分步骤";
        StepVerifier.create(matcher.match(input, accountId))
                .assertNext(node -> assertInstanceOf(TaskNode.class, node))
                .verifyComplete();
    }

    // ===== extractSteps 单元测试 =====

    @Test
    void extractStepsWithNumberedList() {
        var steps = DefaultTreeMatcher.extractSteps("1. 设计\n2. 开发\n3. 测试");
        assertEquals(3, steps.size());
        assertEquals("设计", steps.get(0).content());
        assertEquals("开发", steps.get(1).content());
        assertEquals("测试", steps.get(2).content());
    }

    @Test
    void extractStepsWithDashList() {
        var steps = DefaultTreeMatcher.extractSteps("- 需求分析\n- 架构设计");
        assertEquals(2, steps.size());
        assertEquals("需求分析", steps.get(0).content());
        assertEquals("架构设计", steps.get(1).content());
    }

    @Test
    void extractStepsWithMixedContent() {
        var steps = DefaultTreeMatcher.extractSteps("普通文字\n1. 第一步\n中间说明\n2. 第二步");
        assertEquals(2, steps.size());
    }

    @Test
    void extractStepsEmptyForPlainText() {
        var steps = DefaultTreeMatcher.extractSteps("这是一段普通的文字，没有列表格式");
        assertTrue(steps.isEmpty());
    }
}
