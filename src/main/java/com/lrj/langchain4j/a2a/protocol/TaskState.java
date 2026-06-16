package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A2A 任务状态。序列化用带连字符的 wire value（{@code input-required}）—— {@code @JsonValue} 控制。
 * 与项目内部 {@code async.TaskStatus} 的映射见 {@code A2aMapper}。{@code INPUT_REQUIRED} 本期预留不接线。
 */
public enum TaskState {
    SUBMITTED("submitted"),
    WORKING("working"),
    INPUT_REQUIRED("input-required"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    FAILED("failed"),
    UNKNOWN("unknown");

    private final String value;

    TaskState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
