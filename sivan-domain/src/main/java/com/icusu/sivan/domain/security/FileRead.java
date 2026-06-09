package com.icusu.sivan.domain.security;

public record FileRead(String path, String purpose) implements Action {
    @Override public String type() { return "file_read"; }
}
