package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A2A Message —— 一次发言。{@code role} 为 {@code "user"}（入站）或 {@code "agent"}（出站）。
 * {@code kind} 恒为 {@code "message"}（A2A 用 kind 区分 Message vs Task）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aMessage(String role,
                         List<Part> parts,
                         String messageId,
                         String taskId,
                         String contextId,
                         Map<String, Object> metadata) {

    @JsonProperty("kind")
    public String kind() {
        return "message";
    }

    /** 出站便捷构造：把一段纯文本包成 agent message。 */
    public static A2aMessage agentText(String text, String taskId, String contextId) {
        return new A2aMessage("agent", List.of(Part.text(text)),
                UUID.randomUUID().toString(), taskId, contextId, null);
    }

    /** 取所有 text part 拼起来的纯文本（入站取用户问题用）。 */
    public String textContent() {
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p != null && "text".equals(p.kind()) && p.text() != null) {
                sb.append(p.text());
            }
        }
        return sb.toString();
    }
}
