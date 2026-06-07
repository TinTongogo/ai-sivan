package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatPromptsTest {

    @Test
    void chatSystem_hasSivanPersona() {
        Prompt p = ChatPrompts.CHAT_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertTrue(p.content().contains("Sivan"));
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
    }

    @Test
    void polishSystem_hasSivanPersona() {
        Prompt p = ChatPrompts.POLISH_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
    }

    @Test
    void intentClassifySystem_hasSivanPersona() {
        Prompt p = ChatPrompts.INTENT_CLASSIFY_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertTrue(p.content().contains("CHAT"));
        assertTrue(p.content().contains("SINGLE_AGENT"));
        assertTrue(p.content().contains("SQUAD"));
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
    }

    @Test
    void intentClassifyUser_containsUserMessage() {
        Prompt p = ChatPrompts.intentClassifyUser("帮我写一篇文档");
        assertTrue(p.content().contains("帮我写一篇文档"));
        assertEquals(Prompt.CacheStrategy.DYNAMIC, p.cacheStrategy());
    }

    @Test
    void intentClassify_systemPrompt_works() {
        Prompt p = ChatPrompts.INTENT_CLASSIFY_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertTrue(p.content().contains("CHAT"));
    }

    @Test
    void semanticRoute_hasSivanPersona() {
        Prompt p = ChatPrompts.SEMANTIC_ROUTE_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertEquals(Prompt.OutputFormat.JSON_OBJECT, p.outputFormat());
    }

    @Test
    void semanticRouteUser_dynamic() {
        Prompt p = ChatPrompts.semanticRouteUser("a1,a2", "task");
        assertTrue(p.content().contains("task"));
    }

    @Test
    void contextInjection_dynamic() {
        Prompt p = ChatPrompts.contextInjection("ctx");
        assertTrue(p.content().contains("ctx"));
    }
}
