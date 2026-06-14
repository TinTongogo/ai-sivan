package com.icusu.sivan.domain.forest.vo;

public record SpeechRecogRequest(byte[] audio, String format, String language) {}
