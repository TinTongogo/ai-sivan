package com.icusu.sivan.domain.forest.context;

/**
 * 传递模式 — 控制事件如何推送给前端。
 * <p>
 * <b>STREAM</b>：对话模式，实时 SSE 推送，前端逐 token 渲染。<br>
 * <b>SUMMARY</b>：异步模式，不推 SSE，完成后通知。进度通过 API 查询。
 */
public enum Delivery {
    STREAM,
    SUMMARY
}
