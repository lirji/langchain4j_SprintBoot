package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * A2A Artifact —— 任务的产出物（一组 {@link Part}）。MVP 只产文本 artifact。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(String artifactId, String name, List<Part> parts) {

    public static Artifact text(String name, String text) {
        return new Artifact(UUID.randomUUID().toString(), name, List.of(Part.text(text)));
    }
}
