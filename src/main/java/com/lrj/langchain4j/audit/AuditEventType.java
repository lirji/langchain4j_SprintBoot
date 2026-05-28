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
    WEBHOOK_FAILED("webhook.failed");

    private final String wire;

    AuditEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
