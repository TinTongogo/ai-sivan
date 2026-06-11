package com.icusu.sivan.domain.forest.service;

public sealed interface SpeechRecogEvent {
    record Chunk(String text, boolean isFinal) implements SpeechRecogEvent {}
    record Completed(String fullText, String language) implements SpeechRecogEvent {}
    record Error(Throwable cause) implements SpeechRecogEvent {}
}
