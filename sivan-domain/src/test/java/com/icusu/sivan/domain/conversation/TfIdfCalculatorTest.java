package com.icusu.sivan.domain.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TF-IDF 计算器测试。 */
class TfIdfCalculatorTest {

    private final TfIdfCalculator calculator = new TfIdfCalculator();

    @Test
    /** 字符 bi-gram 提取应正确处理中文文本。 */
    void extractBigrams_shouldHandleChinese() {
        Map<String, Integer> bigrams = TfIdfCalculator.extractBigrams("今天天气好");

        assertTrue(bigrams.containsKey("今天"));
        assertTrue(bigrams.containsKey("天天"));
        assertTrue(bigrams.containsKey("天气"));
        assertTrue(bigrams.containsKey("气好"));
        assertEquals(1, (int) bigrams.get("今天"));
    }

    @Test
    /** 空文本应返回空 map。 */
    void extractBigrams_shouldReturnEmptyForBlank() {
        assertTrue(TfIdfCalculator.extractBigrams(null).isEmpty());
        assertTrue(TfIdfCalculator.extractBigrams("").isEmpty());
        assertTrue(TfIdfCalculator.extractBigrams("   ").isEmpty());
    }

    @Test
    /** 相同文本的余弦相似度应为 1.0。 */
    void cosineSimilarity_identicalTexts() {
        double sim = calculator.similarity("今天天气怎么样", "今天天气怎么样");
        assertTrue(Math.abs(sim - 1.0) < 0.001, "identical similarity=" + sim);
    }

    @Test
    /** 完全不相关的文本相似度应接近 0。 */
    void cosineSimilarity_unrelatedTexts() {
        double sim = calculator.similarity("今天天气怎么样", "asdfghjklqwertyuiop");
        assertTrue(sim < 0.1, "unrelated similarity=" + sim);
    }

    @Test
    /** 相同话题的文本应有可测量的相似度。 */
    void cosineSimilarity_sameTopic() {
        double sim = calculator.similarity(
                "中国的经济增长速度在2024年达到预期目标",
                "中国经济保持了稳定增长的态势");
        assertTrue(sim > 0.05, "same topic similarity=" + sim);
    }

    @Test
    /** 话题跳转场景：A→B→A 中的 A 文本应有可测量的相似度。 */
    void cosineSimilarity_topicJumpBack() {
        String topicA1 = "请帮我分析一下第二季度的销售数据";
        String topicA2 = "销售数据中哪个产品线增长最快";

        double sim = calculator.similarity(topicA1, topicA2);
        assertTrue(sim > 0.05, "same topic jump-back similarity=" + sim);
    }

    @Test
    /** 累积文本（多消息拼接）的相似度应高于单句比较。 */
    void cosineSimilarity_accumulatedText() {
        String accumulated = "请帮我分析第二季度销售数据，看看各个产品线的表现 销售数据显示第二季度营收增长15%，其中核心产品线增长最快";
        String followUp = "销售数据中哪个产品线增长最快";

        double singleSim = calculator.similarity(
                "请帮我分析第二季度销售数据，看看各个产品线的表现",
                followUp);
        double accumSim = calculator.similarity(accumulated, followUp);

        assertTrue(accumSim > singleSim,
                "accumulated(" + accumSim + ") should be > single(" + singleSim + ")");
        assertTrue(accumSim > 0.20, "accumulated topic similarity=" + accumSim);
    }

    @Test
    /** Top-K 高频 bi-gram 提取。 */
    void topTerms_shouldExtractFrequentBigrams() {
        List<String> texts = List.of(
                "今天天气很好",
                "今天天气不错",
                "今天天气晴朗"
        );

        List<String> terms = calculator.topTerms(texts, 2);
        assertFalse(terms.isEmpty());
        assertTrue(terms.contains("今天"), "top terms should include '今天', got: " + terms);
    }

    @Test
    /** 多文档 IDF 构建不应抛出异常。 */
    void buildIdf_shouldHandleMultipleDocuments() {
        List<String> docs = List.of("今天天气好", "经济数据发布", "今天经济数据");
        Map<String, Double> idf = calculator.buildIdf(docs);

        assertNotNull(idf);
        assertFalse(idf.isEmpty());
    }
}
