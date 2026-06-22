package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A Task 的 status 对象：当前 {@link TaskState} + 可选关联 message + ISO-8601 时间戳。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTaskStatus(TaskState state, A2aMessage message, String timestamp) {

    public static A2aTaskStatus of(TaskState state, String timestamp) {
        return new A2aTaskStatus(state, null, timestamp);
    }
}
