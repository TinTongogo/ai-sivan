package com.icusu.sivan.domain.forest.service;

public record VideoPrompt(String prompt, String size, int durationSec) {
    public static VideoPrompt of(String prompt) {
        return new VideoPrompt(prompt, "1920x1080", 10);
    }
}
