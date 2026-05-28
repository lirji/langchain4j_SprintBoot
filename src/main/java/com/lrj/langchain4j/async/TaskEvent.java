package com.lrj.langchain4j.async;

/**
 * Spring {@code ApplicationEvent}：每次 task 状态变更都 fire 一次。订阅方：
 * <ul>
 *   <li>{@code WebhookDispatcher} — terminal 状态时回调客户端 URL</li>
 *   <li>{@code TaskSseService}    — 推送给已经订阅 {@code GET /tasks/{id}/stream} 的客户端</li>
 * </ul>
 *
 * <p>不用 Spring 内置 {@code ApplicationEvent} 类（已废弃），用 record 直接发布即可
 * （Spring 6+ 支持 POJO event）。
 *
 * @param task 状态变更**之后**的 task 快照；订阅方按它的 status 决定 terminal 处理
 */
public record TaskEvent(AsyncTask task) {
}
