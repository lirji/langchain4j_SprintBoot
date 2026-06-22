package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A2A Task —— 一次（可能长跑的）协作的载体。{@code kind} 恒为 {@code "task"}。
 * {@code contextId} 关联同一会话的多个 task；{@code artifacts} 为终态产出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTask(String id,
                      String contextId,
                      A2aTaskStatus status,
                      List<Artifact> artifacts,
                      Map<String, Object> metadata) {

    @JsonProperty("kind")
    public String kind() {
        return "task";
    }
}
