package com.icusu.sivan.domain.security;

/**
 * 安全动作 — 每种需要安全审查的操作对应一个实现。
 */
public interface Action {
    String type();
}
