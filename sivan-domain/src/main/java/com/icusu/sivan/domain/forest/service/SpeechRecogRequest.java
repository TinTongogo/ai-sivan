package com.icusu.sivan.domain.forest.service;

public record SpeechRecogRequest(byte[] audio, String format, String language) {}
