package com.icusu.sivan.domain.security;

public interface Policy<T extends Action> {
    void validate(T action, SecurityContext ctx);
    Class<T> actionType();
    default String requiredPermission() { return "*"; }
}
