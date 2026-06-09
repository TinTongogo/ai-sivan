package com.icusu.sivan.domain.security;

public record ShellExec(String command, String[] args) implements Action {
    @Override public String type() { return "shell_exec"; }
}
