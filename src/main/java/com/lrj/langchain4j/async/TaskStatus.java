package com.lrj.langchain4j.async;

/** 异步任务状态机：{@code PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED}。终态不可变。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
