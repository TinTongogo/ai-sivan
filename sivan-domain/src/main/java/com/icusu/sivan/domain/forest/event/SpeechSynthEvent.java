package com.icusu.sivan.domain.forest.event;

public sealed interface SpeechSynthEvent {
    record AudioChunk(byte[] audio, String format) implements SpeechSynthEvent {}
    record Completed(long durationMs) implements SpeechSynthEvent {}
    record Error(Throwable cause) implements SpeechSynthEvent {}
}
