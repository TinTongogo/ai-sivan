package com.icusu.sivan.common.enums;

/**
 * 目标生命周期状态。
 * <ul>
 *   <li>PENDING — 已创建待确认/待开始</li>
 *   <li>ACTIVE — 执行中</li>
 *   <li>PAUSED — 已暂停</li>
 *   <li>COMPLETED — 全部里程碑完成</li>
 *   <li>CANCELLED — 用户取消</li>
 *   <li>FAILED — 执行失败</li>
 * </ul>
 */
public enum GoalStatus {PENDING, ACTIVE, PAUSED, COMPLETED, CANCELLED, FAILED}
