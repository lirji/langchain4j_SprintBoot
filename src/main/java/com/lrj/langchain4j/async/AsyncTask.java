package com.lrj.langchain4j.async;

import java.time.Instant;
import java.util.Map;

/**
 * 异步任务的不可变快照。每次状态变更生成新的 record（{@link TaskStore} 整体替换）—— 避免并发
 * 突变。{@code result} 是任意业务输出（multi-agent 是 {@code MultiAgentService.Run}）。
 */
public record AsyncTask(
        String taskId,
        String tenantId,
        String userId,
        TaskKind kind,
        TaskStatus status,
        Map<String, Object> input,
        Object result,
        String error,
        String webhookUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    public AsyncTask withStatus(TaskStatus newStatus, Object newResult, String newError) {
        return new AsyncTask(taskId, tenantId, userId, kind, newStatus, input,
                newResult, newError, webhookUrl, createdAt, Instant.now(),
                newStatus.isTerminal() ? Instant.now() : finishedAt);
    }
}
