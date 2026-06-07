package com.icusu.sivan.infra.agent.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShortIdGeneratorTest {

    @Test
    void generate_shouldReturnAdjectiveNounFormat() {
        String result = ShortIdGenerator.generate();
        assertTrue(result.matches("[a-z]+-[a-z]+"), "格式应为 {adj}-{noun}: " + result);
    }

    @Test
    void generate_shouldNotExceedMaxLength() {
        for (int i = 0; i < 100; i++) {
            String result = ShortIdGenerator.generate();
            assertTrue(result.length() <= 20, "长度应 ≤ 20: " + result);
        }
    }

    @Test
    void generateWithSuffix_shouldEndWithThreeAlphanumeric() {
        String result = ShortIdGenerator.generateWithSuffix();
        assertTrue(result.matches("[a-z]+-[a-z]+-[a-z0-9]{3}"),
                "格式应为 {adj}-{noun}-{3位后缀}: " + result);
    }

    @Test
    void generate_shouldProduceHighCardinality() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(ShortIdGenerator.generate());
        }
        // 1024 组合中取 1000 次，生日悖论下期望约 640 个唯一值
        assertTrue(seen.size() > 500, "1000 次生成应有 >500 个唯一值，实际: " + seen.size());
    }
}
