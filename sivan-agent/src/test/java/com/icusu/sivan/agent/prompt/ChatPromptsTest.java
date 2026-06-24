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
