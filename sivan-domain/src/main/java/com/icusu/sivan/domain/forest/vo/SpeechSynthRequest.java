package com.icusu.sivan.domain.forest.vo;

public record SpeechSynthRequest(String text, String voice, double speed) {
    public static SpeechSynthRequest of(String text) {
        return new SpeechSynthRequest(text, "default", 1.0);
    }
}
