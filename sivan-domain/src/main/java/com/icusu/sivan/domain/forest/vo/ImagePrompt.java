package com.icusu.sivan.domain.forest.vo;

public record ImagePrompt(String prompt, String negativePrompt, String size, int n, String style) {
    public static ImagePrompt of(String prompt) {
        return new ImagePrompt(prompt, null, "1024x1024", 1, null);
    }
}
