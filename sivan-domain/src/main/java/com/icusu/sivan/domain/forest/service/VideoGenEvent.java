package com.icusu.sivan.domain.forest.service;

public sealed interface VideoGenEvent {
    record Progress(int percent) implements VideoGenEvent {}
    record Completed(String url, String format, int durationSec) implements VideoGenEvent {}
    record Error(Throwable cause) implements VideoGenEvent {}
}
