package com.icusu.sivan.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileLearnerTest {

    @Test
    void parseExpertise_validJson() {
        var tags = ProfileLearner.parseExpertise(
                "{\"expertise\":[\"Spring Boot\",\"PostgreSQL\",\"Java\"]}");
        assertEquals(List.of("Spring Boot", "PostgreSQL", "Java"), tags);
    }

    @Test
    void parseExpertise_emptyArray() {
        var tags = ProfileLearner.parseExpertise("{\"expertise\":[]}");
        assertTrue(tags.isEmpty());
    }

    @Test
    void parseExpertise_markdownBlock() {
        var tags = ProfileLearner.parseExpertise(
                "```json\n{\"expertise\":[\"Python\",\"FastAPI\"]}\n```");
        assertEquals(List.of("Python", "FastAPI"), tags);
    }

    @Test
    void parseExpertise_nullInput() {
        assertTrue(ProfileLearner.parseExpertise(null).isEmpty());
    }

    @Test
    void parseExpertise_invalidJson() {
        assertTrue(ProfileLearner.parseExpertise("这不是JSON").isEmpty());
    }

    @Test
    void parseExpertise_noExpertiseField() {
        var tags = ProfileLearner.parseExpertise("{\"other\":\"value\"}");
        assertTrue(tags.isEmpty());
    }
}
