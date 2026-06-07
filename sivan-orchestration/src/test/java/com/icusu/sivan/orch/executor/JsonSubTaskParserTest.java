package com.icusu.sivan.orch.executor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSubTaskParserTest {

    // ── extractJson ──

    @Test
    void extractJson_null返回null() {
        assertNull(JsonSubTaskParser.extractJson(null));
    }

    @Test
    void extractJson_无JSON返回null() {
        assertNull(JsonSubTaskParser.extractJson("普通文本"));
    }

    @Test
    void extractJson_提取首个JSON对象() {
        String text = "前文 {\"key\": \"value\"} 后文";
        assertEquals("{\"key\": \"value\"}", JsonSubTaskParser.extractJson(text));
    }

    @Test
    void extractJson_嵌套JSON提取完整() {
        String json = "{\"outer\": {\"inner\": 1}}";
        assertEquals(json, JsonSubTaskParser.extractJson(json));
    }

    @Test
    void extractJson_带换行和多层() {
        String text = "结果：\n{\n  \"name\": \"test\",\n  \"items\": [1,2,3]\n}\n完毕";
        String extracted = JsonSubTaskParser.extractJson(text);
        assertTrue(extracted.contains("\"name\""));
        assertTrue(extracted.contains("\"items\""));
    }

    // ── findMatchingBrace ──

    @Test
    void findMatchingBrace_起始字符不是大括号返回负一() {
        assertEquals(-1, JsonSubTaskParser.findMatchingBrace("abc", 0));
    }

    @Test
    void findMatchingBrace_简单匹配() {
        assertEquals(4, JsonSubTaskParser.findMatchingBrace("{abc}", 0));
    }

    @Test
    void findMatchingBrace_嵌套匹配() {
        assertEquals(9, JsonSubTaskParser.findMatchingBrace("{a{b{c}}d}", 0));
    }

    @Test
    void findMatchingBrace_不闭合返回负一() {
        assertEquals(-1, JsonSubTaskParser.findMatchingBrace("{abc", 0));
    }

    @Test
    void findMatchingBrace_从中间位置开始() {
        assertEquals(7, JsonSubTaskParser.findMatchingBrace("x{a{b}c}y", 1));
    }

    // ── extractDouble ──

    @Test
    void extractDouble_正常提取() {
        String json = "{\"score\": 0.85}";
        assertEquals(0.85, JsonSubTaskParser.extractDouble(json, "score", 0.0), 0.001);
    }

    @Test
    void extractDouble_键不存在返回默认值() {
        String json = "{\"other\": 1}";
        assertEquals(0.5, JsonSubTaskParser.extractDouble(json, "missing", 0.5), 0.001);
    }

    @Test
    void extractDouble_值非数字返回默认值() {
        String json = "{\"key\": \"text\"}";
        assertEquals(0.0, JsonSubTaskParser.extractDouble(json, "key", 0.0), 0.001);
    }

    // ── extractString ──

    @Test
    void extractString_正常提取() {
        String json = "{\"name\": \"hello\"}";
        assertEquals("hello", JsonSubTaskParser.extractString(json, "name", ""));
    }

    @Test
    void extractString_键不存在返回默认值() {
        String json = "{\"other\": 1}";
        assertEquals("def", JsonSubTaskParser.extractString(json, "missing", "def"));
    }

    // ── extractStringList ──

    @Test
    void extractStringList_正常提取() {
        String json = "{\"items\": [\"a\", \"b\", \"c\"]}";
        assertEquals(List.of("a", "b", "c"), JsonSubTaskParser.extractStringList(json, "items"));
    }

    @Test
    void extractStringList_键不存在返回空列表() {
        String json = "{\"other\": 1}";
        assertTrue(JsonSubTaskParser.extractStringList(json, "missing").isEmpty());
    }

    @Test
    void extractStringList_空数组返回空列表() {
        String json = "{\"items\": []}";
        assertTrue(JsonSubTaskParser.extractStringList(json, "items").isEmpty());
    }

    // ── extractIntList ──

    @Test
    void extractIntList_正常提取() {
        String json = "{\"ids\": [1, 2, 3]}";
        assertEquals(List.of(1, 2, 3), JsonSubTaskParser.extractIntList(json, "ids"));
    }

    @Test
    void extractIntList_无效数字跳过() {
        String json = "{\"ids\": [1, \"x\", 3]}";
        assertEquals(List.of(1, 3), JsonSubTaskParser.extractIntList(json, "ids"));
    }

    @Test
    void extractIntList_空数组() {
        String json = "{\"ids\": []}";
        assertTrue(JsonSubTaskParser.extractIntList(json, "ids").isEmpty());
    }

    // ── parseSubTasks ──

    @Test
    void parseSubTasks_标准格式() {
        String content = "计划如下：\n```\n{\n  \"subtasks\": [\n    {\"id\": 1, \"goal\": \"任务A\", \"input\": \"输入A\", \"expected_output\": \"输出A\", \"depends_on\": []},\n    {\"id\": 2, \"goal\": \"任务B\", \"input\": \"输入B\", \"expected_output\": \"输出B\", \"depends_on\": [1]}\n  ]\n}\n```";
        List<JsonSubTaskParser.SubTask> tasks = JsonSubTaskParser.parseSubTasks(content);
        assertEquals(2, tasks.size());
        assertEquals(1, tasks.get(0).id());
        assertEquals("任务A", tasks.get(0).goal());
        assertEquals(List.of(), tasks.get(0).dependsOn());
        assertEquals(2, tasks.get(1).id());
        assertEquals(List.of(1), tasks.get(1).dependsOn());
    }

    @Test
    void parseSubTasks_无JSON内容返回空() {
        assertTrue(JsonSubTaskParser.parseSubTasks("普通文本").isEmpty());
    }

    @Test
    void parseSubTasks_无subtasks键返回空() {
        assertTrue(JsonSubTaskParser.parseSubTasks("{\"other\": 1}").isEmpty());
    }

    @Test
    void parseSubTasks_id为零跳过() {
        String content = "{\"subtasks\": [{\"id\": 0, \"goal\": \"跳过\"}]}";
        assertTrue(JsonSubTaskParser.parseSubTasks(content).isEmpty());
    }

    // ── parseSynthesisResult ──

    @Test
    void parseSynthesisResult_标准格式() {
        String content = "{\"confidence\": 0.92, \"majorityOpinion\": \"方案A\", \"dissentPoints\": [\"风险高\"], \"dissenters\": [\"agent2\"], \"conclusion\": \"选A\"}";
        JsonSubTaskParser.SynthesisResult result = JsonSubTaskParser.parseSynthesisResult(content);
        assertEquals(0.92, result.confidence(), 0.001);
        assertEquals("方案A", result.majorityOpinion());
        assertEquals(List.of("风险高"), result.dissentPoints());
        assertEquals(List.of("agent2"), result.dissenters());
        assertEquals("选A", result.conclusion());
    }

    @Test
    void parseSynthesisResult_无JSON使用原文作为结论() {
        String text = "我认为应该采用方案B";
        JsonSubTaskParser.SynthesisResult result = JsonSubTaskParser.parseSynthesisResult(text);
        assertEquals(0.0, result.confidence(), 0.001);
        assertEquals(text, result.conclusion());
    }

    @Test
    void parseSynthesisResult_部分字段() {
        String content = "{\"confidence\": 0.5, \"conclusion\": \"暂定\"}";
        JsonSubTaskParser.SynthesisResult result = JsonSubTaskParser.parseSynthesisResult(content);
        assertEquals(0.5, result.confidence(), 0.001);
        assertEquals("暂定", result.conclusion());
        assertTrue(result.dissentPoints().isEmpty());
    }
}
