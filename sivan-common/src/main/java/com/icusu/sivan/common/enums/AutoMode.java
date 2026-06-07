package com.icusu.sivan.common.enums;

/**
 * 目标自动推进模式。
 * <ul>
 *   <li>AUTO — 全自动推进，Task 完成后立即执行下一个</li>
 *   <li>CONFIRM_MILESTONE — 每个里程碑完成后等待用户确认</li>
 *   <li>CONFIRM_EACH_TASK — 每个 Task 完成后等待用户确认</li>
 * </ul>
 */
public enum AutoMode {AUTO, CONFIRM_MILESTONE, CONFIRM_EACH_TASK}
