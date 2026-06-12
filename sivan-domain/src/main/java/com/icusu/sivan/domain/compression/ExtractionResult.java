package com.icusu.sivan.domain.compression;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 提取结果 — 从对话中提取的结构化信息。
 * <p>
 * 设计文档 4.4 节。可转换为 StructuredMemory 列表持久化。
 */
public class ExtractionResult {

    private final List<String> decisions = new ArrayList<>();
    private final List<String> facts = new ArrayList<>();
    private final List<String> techs = new ArrayList<>();

    public List<String> getDecisions() { return decisions; }
    public List<String> getFacts() { return facts; }
    public List<String> getTechs() { return techs; }

    public boolean isEmpty() {
        return decisions.isEmpty() && facts.isEmpty() && techs.isEmpty();
    }

    /** 转换为 StructuredMemory 列表。 */
    public List<StructuredMemory> toMemoryRecords(UUID accountId, String conversationId) {
        List<StructuredMemory> records = new ArrayList<>();
        for (String d : decisions) {
            if (!d.isBlank()) records.add(StructuredMemory.decision(accountId, d, conversationId));
        }
        for (String f : facts) {
            if (!f.isBlank()) records.add(StructuredMemory.fact(accountId, f, conversationId));
        }
        for (String t : techs) {
            if (!t.isBlank()) records.add(StructuredMemory.tech(accountId, t, conversationId));
        }
        return records;
    }
}
