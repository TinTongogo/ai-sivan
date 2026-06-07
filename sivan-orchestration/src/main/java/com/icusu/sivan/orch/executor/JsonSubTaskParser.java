package com.icusu.sivan.orch.executor;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LLM 输出 JSON 解析工具。从 LLM 自由文本中提取 JSON 对象，
 * 解析子任务列表（HIERARCHICAL 模式）和综合结果（CONSENSUS 模式）。
 */
@Slf4j
public final class JsonSubTaskParser {

    private JsonSubTaskParser() {
    }

    // ========== JSON 提取 ==========

    /**
     * 从文本中提取首个 JSON 对象字符串。
     */
    public static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    /**
     * 在字符串中查找匹配的闭合大括号位置。
     */
    public static int findMatchingBrace(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') return -1;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ========== 基本类型提取 ==========

    public static double extractDouble(String json, String key, double defaultValue) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return defaultValue;
        int end = json.indexOf(',', colon);
        if (end < 0) end = json.indexOf('}', colon);
        if (end < 0) return defaultValue;
        try {
            return Double.parseDouble(json.substring(colon + 1, end).strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String extractString(String json, String key, String defaultValue) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return defaultValue;
        int valStart = json.indexOf('"', colon + 1);
        if (valStart < 0) return defaultValue;
        int valEnd = json.indexOf('"', valStart + 1);
        if (valEnd < 0) return defaultValue;
        return json.substring(valStart + 1, valEnd);
    }

    public static List<String> extractStringList(String json, String key) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return List.of();
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return List.of();
        int arrStart = json.indexOf('[', colon);
        if (arrStart < 0) return List.of();
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return List.of();

        String content = json.substring(arrStart + 1, arrEnd);
        List<String> result = new ArrayList<>();
        int s = 0;
        while (true) {
            int qs = content.indexOf('"', s);
            if (qs < 0) break;
            int qe = content.indexOf('"', qs + 1);
            if (qe < 0) break;
            result.add(content.substring(qs + 1, qe));
            s = qe + 1;
        }
        return result;
    }

    public static List<Integer> extractIntList(String json, String key) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return List.of();
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return List.of();
        int arrStart = json.indexOf('[', colon);
        if (arrStart < 0) return List.of();
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return List.of();
        String content = json.substring(arrStart + 1, arrEnd).strip();
        if (content.isEmpty()) return List.of();
        return Arrays.stream(content.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .<Integer>mapMulti((s, c) -> {
                    try {
                        c.accept(Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {
                    }
                })
                .toList();
    }

    // ========== 子任务解析 ==========

    /**
     * 从 LLM 输出中解析子任务列表。
     */
    public static List<SubTask> parseSubTasks(String content) {
        String json = extractJson(content);
        if (json == null) return List.of();
        try {
            String subtasksKey = "\"subtasks\"";
            int idx = json.indexOf(subtasksKey);
            if (idx < 0) return List.of();
            int arrStart = json.indexOf('[', idx + subtasksKey.length());
            int arrEnd = json.lastIndexOf(']');
            if (arrStart < 0 || arrEnd <= arrStart) return List.of();
            return parseSubTaskArray(json.substring(arrStart + 1, arrEnd));
        } catch (Exception e) {
            log.warn("解析子任务 JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<SubTask> parseSubTaskArray(String arrContent) {
        List<SubTask> result = new ArrayList<>();
        int pos = 0;
        while (true) {
            int objStart = arrContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrContent, objStart);
            if (objEnd < 0) break;
            String obj = arrContent.substring(objStart, objEnd + 1);
            int id = (int) extractDouble(obj, "id", -1);
            if (id > 0) {
                result.add(new SubTask(id, extractString(obj, "goal", ""),
                        extractString(obj, "input", ""),
                        extractString(obj, "expected_output", ""),
                        extractIntList(obj, "depends_on")));
            }
            pos = objEnd + 1;
        }
        return result;
    }

    // ========== 综合结果解析 ==========

    /**
     * 从 LLM 输出中解析综合结果（CONSENSUS 模式）。
     */
    public static SynthesisResult parseSynthesisResult(String content) {
        String json = extractJson(content);
        if (json != null) {
            try {
                double confidence = extractDouble(json, "confidence", 0.0);
                String majority = extractString(json, "majorityOpinion", "");
                String conclusion = extractString(json, "conclusion", content);
                List<String> dissentPoints = extractStringList(json, "dissentPoints");
                List<String> dissenters = extractStringList(json, "dissenters");
                return new SynthesisResult(confidence, majority, dissentPoints, dissenters, conclusion);
            } catch (Exception e) {
                log.warn("解析综合 JSON 失败，使用低置信度兜底: {}", e.getMessage());
            }
        }
        return new SynthesisResult(0.0, "", List.of(), List.of(), content);
    }

    // ========== 内部数据结构 ==========

    /**
     * 综合结果。
     */
    public record SynthesisResult(double confidence, String majorityOpinion,
                                  List<String> dissentPoints,
                                  List<String> dissenters, String conclusion) {
    }

    /**
     * 子任务。
     */
    public record SubTask(int id, String goal, String input, String expectedOutput,
                          List<Integer> dependsOn) {
    }
}
