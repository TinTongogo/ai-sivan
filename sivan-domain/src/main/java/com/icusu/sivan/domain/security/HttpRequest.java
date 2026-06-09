package com.icusu.sivan.domain.security;

public record HttpRequest(String url, String method) implements Action {
    @Override public String type() { return "http_request"; }
}
