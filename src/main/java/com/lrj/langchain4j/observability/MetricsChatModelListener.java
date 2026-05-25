package com.lrj.langchain4j.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.concurrent.TimeUnit;

/**
 * Minimal Micrometer integration. Records LLM call counters, latency, and token usage
 * under {@code gen_ai.client.*} (loose OpenTelemetry GenAI semantic-convention naming).
 * Re-implemented in-tree because {@code langchain4j-micrometer} is not yet published.
 */
public class MetricsChatModelListener implements ChatModelListener {

    private static final String START_KEY = "lrj.metrics.startNanos";

    private final MeterRegistry registry;

    public MetricsChatModelListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(START_KEY, System.nanoTime());
        registry.counter("gen_ai.client.requests",
                Tags.of("model", safe(ctx.chatRequest().parameters().modelName()),
                        "provider", safe(ctx.modelProvider().toString())))
                .increment();
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        ChatResponseMetadata md = ctx.chatResponse().metadata();
        Tags tags = Tags.of("model", safe(md.modelName()),
                "provider", safe(ctx.modelProvider().toString()));
        recordDuration(ctx.attributes().get(START_KEY), tags);
        TokenUsage tu = md.tokenUsage();
        if (tu != null) {
            if (tu.inputTokenCount() != null) {
                registry.counter("gen_ai.client.token.usage", tags.and("type", "input"))
                        .increment(tu.inputTokenCount());
            }
            if (tu.outputTokenCount() != null) {
                registry.counter("gen_ai.client.token.usage", tags.and("type", "output"))
                        .increment(tu.outputTokenCount());
            }
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Tags tags = Tags.of("model", safe(ctx.chatRequest().parameters().modelName()),
                "provider", safe(ctx.modelProvider().toString()),
                "error", ctx.error().getClass().getSimpleName());
        registry.counter("gen_ai.client.errors", tags).increment();
        recordDuration(ctx.attributes().get(START_KEY), tags);
    }

    private void recordDuration(Object startNanos, Tags tags) {
        if (!(startNanos instanceof Long start)) return;
        long nanos = System.nanoTime() - start;
        registry.timer("gen_ai.client.operation.duration", tags).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
