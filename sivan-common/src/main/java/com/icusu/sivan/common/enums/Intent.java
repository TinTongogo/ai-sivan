package com.icusu.sivan.common.enums;

/**
 * 路由意图枚举，表示用户消息的分类结果。
 * <ul>
 *   <li>CHAT — 普通对话/闲聊，走聊天路径</li>
 *   <li>SINGLE_AGENT — 可由单个智能体完成的任务</li>
 *   <li>SQUAD — 需要多智能体编排的复杂任务</li>
 * </ul>
 */
public enum Intent {CHAT, SINGLE_AGENT, SQUAD}
