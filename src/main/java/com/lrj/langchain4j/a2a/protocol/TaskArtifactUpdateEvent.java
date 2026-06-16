package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A 流式产出增量事件（{@code message/stream} 的 SSE 帧之一）。{@code kind="artifact-update"}，
 * {@code append=true} 表示本帧是对同一 artifact 的增量追加（流式逐 token）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskArtifactUpdateEvent(String taskId,
                                      String contextId,
                                      Artifact artifact,
                                      boolean append,
                                      boolean lastChunk) {

    @JsonProperty("kind")
    public String kind() {
        return "artifact-update";
    }
}
