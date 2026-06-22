package com.lrj.langchain4j.audit;

/**
 * 审计事件类型枚举 —— 用枚举而不是 free string,避免拼写漂移。
 * 落 JSON 时用 {@link #wire} 作为 {@code type} 字段值。
 */
public enum AuditEventType {
    LLM_REQUEST("llm.request"),
    LLM_ERROR("llm.error"),

    AUTH_DENIED("auth.denied"),
    RATE_LIMITED("rate.limited"),
    TOKEN_BUDGET_EXHAUSTED("budget.exhausted"),

    GUARDRAIL_INJECTION_DETECTED("guardrail.injection_detected"),
    GUARDRAIL_PII_REDACTED("guardrail.pii_redacted"),

    DOCUMENT_UPLOADED("doc.uploaded"),
    DOCUMENT_DELETED("doc.deleted"),

    ASYNC_TASK_SUBMITTED("task.submitted"),
    ASYNC_TASK_FINISHED("task.finished"),
    ASYNC_TASK_CANCELLED("task.cancelled"),

    WEBHOOK_DELIVERED("webhook.delivered"),
    WEBHOOK_FAILED("webhook.failed"),

    NL2SQL_QUERY("nl2sql.query"),

    WORKFLOW_STARTED("workflow.started"),
    APPROVAL_REQUESTED("approval.requested"),
    APPROVAL_GRANTED("approval.granted"),
    APPROVAL_REJECTED("approval.rejected"),
    APPROVAL_TIMEOUT("approval.timeout"),
    WORKFLOW_COMPLETED("workflow.completed"),
    // LLM 在 ServiceTask 内重试耗尽后写降级兜底答复（#3 失败补偿：不回滚人工决定，改为降级落地）
    REPLY_DEGRADED("reply.degraded"),
    // 历史实例 + WF_REPLY 行按保留期清理（#4 历史表无限增长）
    WORKFLOW_HISTORY_PRUNED("workflow.history_pruned"),
    // 终态回推可靠投递（#8 outbox）：投递成功 / 单次失败待重试 / 重试耗尽进 DLQ
    WORKFLOW_PUSH_DELIVERED("workflow.push_delivered"),
    WORKFLOW_PUSH_FAILED("workflow.push_failed"),
    WORKFLOW_PUSH_DEAD("workflow.push_dead"),
    // 按 chatId 清除某租户的工作流持久化数据（#10 PII 合规删除）
    WORKFLOW_DATA_PURGED("workflow.data_purged");

    private final String wire;

    AuditEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
