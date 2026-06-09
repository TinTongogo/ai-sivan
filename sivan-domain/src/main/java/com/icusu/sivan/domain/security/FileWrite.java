package com.icusu.sivan.domain.security;

public record FileWrite(String path, String content) implements Action {
    @Override public String type() { return "file_write"; }
}
