package com.lrj.langchain4j.ai.extract;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Demo POJO for structured-output extraction.
 * {@code @Description} annotations are exported to the JSON Schema sent to the model.
 */
public record Ticket(
        @Description("Short title summarizing the issue, under 80 chars")
        String title,

        @Description("One of: CRITICAL, HIGH, MEDIUM, LOW")
        Priority priority,

        @Description("Free-text category/topic, e.g. billing, auth, performance")
        String category,

        @Description("Concise customer-facing summary of the problem (1-2 sentences)")
        String summary,

        @Description("Up to 5 actionable next steps an engineer should take")
        List<String> nextSteps
) {
    public enum Priority { CRITICAL, HIGH, MEDIUM, LOW }
}
