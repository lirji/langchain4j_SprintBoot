package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A 流式状态变更事件（{@code message/stream} 的 SSE 帧之一）。{@code kind="status-update"}，
 * {@code finalEvent=true} 标记流结束。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatusUpdateEvent(String taskId,
                                    String contextId,
                                    A2aTaskStatus status,
                                    @JsonProperty("final") boolean finalEvent) {

    @JsonProperty("kind")
    public String kind() {
        return "status-update";
    }
}
