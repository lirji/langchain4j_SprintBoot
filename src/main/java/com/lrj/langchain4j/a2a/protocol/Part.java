package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A message / artifact 的内容片段。{@code kind} 是判别字段，MVP 只支持 {@code "text"}
 * （A2A 还有 {@code "file"} / {@code "data"}，后续扩 —— 留 kind 字段即可向后兼容）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Part(String kind, String text) {

    public static Part text(String text) {
        return new Part("text", text);
    }
}
