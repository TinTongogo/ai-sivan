package com.icusu.sivan.application.conversation.service.tree;

import com.icusu.sivan.application.conversation.tree.ConversationTree;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.context.TopicNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 对话话题树测试。 */
class ConversationTreeTest {

    private Message msg(String content, String role) {
        return Message.builder()
                .messageId(UUID.randomUUID())
                .role(role)
                .content(content)
                .build();
    }

    @Test
    /** 单条消息应不足以形成话题。 */
    void buildTopics_singleMessage() {
        ConversationTree tree = new ConversationTree();
        List<TopicNode> topics = tree.buildTopics(List.of(
                msg("今天天气怎么样", "user")
        ));
        assertTrue(topics.isEmpty(), "单条消息不足以成话题");
    }

    @Test
    /** 相同话题的连续消息应合并为一个话题。 */
    void buildTopics_sameTopicShouldMerge() {
        ConversationTree tree = new ConversationTree();
        List<Message> msgs = List.of(
                msg("请分析第二季度的销售数据，包括各个产品线的营收和增长情况", "user"),
                msg("根据销售数据显示，第二季度总营收增长15%，其中核心产品线增长最快达到23%", "assistant")
        );

        List<TopicNode> topics = tree.buildTopics(msgs);
        assertEquals(1, topics.size(), "相同话题应合并");
        assertEquals(2, topics.get(0).size());
    }

    @Test
    /** 话题跳转场景：A→B→A 应识别为两个话题并跳回。 */
    void buildTopics_topicJumpBack() {
        ConversationTree tree = new ConversationTree();
        List<Message> msgs = List.of(
                msg("请分析第二季度销售数据，看看各个产品线的营收和增长情况", "user"),
                msg("根据销售数据显示，第二季度总营收增长15%，其中核心产品线增长最快", "assistant"),
                msg("帮我写一封给客户的邮件，通知他会议改期到下周", "user"),
                msg("邮件已写好，正文已包含改期信息和歉意", "assistant"),
                msg("刚才说的销售数据，能再具体分析一下各个产品线的增长情况吗", "user")
        );

        List<TopicNode> topics = tree.buildTopics(msgs);

        assertFalse(topics.isEmpty());
        // A→B→A 应产生 2 个话题
        assertEquals(2, topics.size(), "A→B→A 应产生2个话题, got: " + topics.size());
        // 跳回后的话题 A 应为活跃
        TopicNode topicA = topics.get(0);
        assertTrue(topicA.isActive(), "跳回后的话题A应为活跃");
        // 话题 A 应包含 3 条消息
        assertEquals(3, topicA.size(), "话题A应包含3条消息");
        // 话题 B 应为 2 条消息
        assertEquals(2, topics.get(1).size(), "话题B应包含2条消息");
    }

    @Test
    /** 完全无关的话题应分割。 */
    void buildTopics_unrelatedTopics() {
        ConversationTree tree = new ConversationTree();
        List<Message> msgs = List.of(
                msg("今天天气怎么样，适合出门散步吗", "user"),
                msg("今天天气晴朗，温度适宜，很适合出门散步", "assistant"),
                msg("帮我解一下这个微积分方程，我不太会做", "user"),
                msg("这个微积分方程可以用分部积分法来解", "assistant")
        );

        List<TopicNode> topics = tree.buildTopics(msgs);
        // 天气和微积分不应合并
        assertTrue(topics.size() >= 2, "不同话题应分割, got: " + topics.size());
    }

    @Test
    /** 多话题序列的话题数不应过多。 */
    void buildTopics_longSequence() {
        ConversationTree tree = new ConversationTree();
        List<Message> msgs = List.of(
                msg("你好，我想咨询一些问题", "user"),
                msg("你好！有什么可以帮助你的？", "assistant"),
                msg("帮我查一下今天的科技新闻", "user"),
                msg("今天的科技新闻头条是AI领域的新突破", "assistant"),
                msg("写一个Python脚本来自动化数据备份", "user"),
                msg("以下是Python数据备份脚本，使用了shutil库", "assistant"),
                msg("这个脚本怎么优化才能处理大文件", "user"),
                msg("可以加入分块读取和增量备份逻辑来优化", "assistant"),
                msg("再帮我看看明天的天气预报", "user"),
                msg("根据气象数据，明天天气晴朗，温度20-25度", "assistant")
        );

        List<TopicNode> topics = tree.buildTopics(msgs);
        assertFalse(topics.isEmpty());
        assertTrue(topics.size() >= 2, "多话题应有至少2个话题, got: " + topics.size());
        assertTrue(topics.size() <= 5, "10条消息不应产生超过5个话题, got: " + topics.size());
    }
}
