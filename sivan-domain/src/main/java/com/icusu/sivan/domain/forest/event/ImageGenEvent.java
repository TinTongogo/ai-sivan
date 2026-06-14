package com.icusu.sivan.domain.forest.event;

public sealed interface ImageGenEvent {
    record Progress(int percent) implements ImageGenEvent {}
    record Completed(String url, String format, int width, int height) implements ImageGenEvent {}
    record Error(Throwable cause) implements ImageGenEvent {}
}
