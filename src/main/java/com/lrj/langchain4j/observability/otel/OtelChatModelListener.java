package com.lrj.langchain4j.observability.otel;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

/**
 * 把每次 chat-model 调用记成一条 OpenTelemetry {@code CLIENT} span，属性遵循 GenAI 语义约定
 * （{@code gen_ai.system} / {@code gen_ai.request.model} / {@code gen_ai.usage.input_tokens} 等）。
 *
 * <p>作为 {@code @Component} 自动加入 {@code LlmConfig} 注入的 {@code List<ChatModelListener>} —— 无需改
 * {@code LlmConfig} / {@code ObservabilityConfig}。跟 {@link com.lrj.langchain4j.observability.MetricsChatModelListener}
 * （Micrometer 指标）正交：一个出聚合指标、一个出 span 树，可同时开。
 *
 * <p>注入的 {@link Tracer} 由 {@link OtelTracingConfig} 提供：{@code app.observability.otel.enabled=true}
 * 时是真实 SDK tracer，否则是 no-op tracer —— 后者 span 全是空操作，本 listener 无需自己判开关。
 *
 * <p>span 在 {@link #onRequest} 起、{@link #onResponse}/{@link #onError} 收，span 句柄经 {@code ctx.attributes()}
 * 跨回调传递（同 Metrics/Logging listener 存开始时间戳的套路）。span 的起止时间即调用时长，另打一个
 * {@code gen_ai.client.duration_ms} 便于在 span 属性里直接看。
 */
@Component
public class OtelChatModelListener implements ChatModelListener {

    /** span 句柄在 ctx.attributes() 里的 key。 */
    private static final String SPAN_KEY = "lrj.otel.span";
    /** 记录调用开始纳秒，用于算 duration 属性。 */
    private static final String START_KEY = "lrj.otel.startNanos";

    private final Tracer tracer;

    public OtelChatModelListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        String model = safe(ctx.chatRequest().parameters().modelName());
        String provider = system(ctx.modelProvider() == null ? null : ctx.modelProvider().name());

        // GenAI 约定 span 名：{operation} {model}
        Span span = tracer.spanBuilder("chat " + model)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.system", provider);
        span.setAttribute("gen_ai.request.model", model);
        span.setAttribute("gen_ai.request.messages", ctx.chatRequest().messages().size());

        // 租户归属：复用现有 TenantContext（onRequest 跑在业务请求线程上，ThreadLocal 尚在）
        TenantContext.Tenant t = TenantContext.current();
        if (t != null) {
            span.setAttribute("tenant.id", safe(t.tenantId()));
            span.setAttribute("enduser.id", safe(t.userId()));
        }

        ctx.attributes().put(SPAN_KEY, span);
        ctx.attributes().put(START_KEY, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = span(ctx.attributes().get(SPAN_KEY));
        if (span == null) return;
        try {
            ChatResponseMetadata md = ctx.chatResponse().metadata();
            if (md.modelName() != null) {
                span.setAttribute("gen_ai.response.model", md.modelName());
            }
            if (md.finishReason() != null) {
                span.setAttribute("gen_ai.response.finish_reasons", md.finishReason().toString());
            }
            TokenUsage tu = md.tokenUsage();
            if (tu != null) {
                if (tu.inputTokenCount() != null) {
                    span.setAttribute("gen_ai.usage.input_tokens", tu.inputTokenCount().longValue());
                }
                if (tu.outputTokenCount() != null) {
                    span.setAttribute("gen_ai.usage.output_tokens", tu.outputTokenCount().longValue());
                }
            }
            recordDuration(span, ctx.attributes().get(START_KEY));
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = span(ctx.attributes().get(SPAN_KEY));
        if (span == null) return;
        try {
            Throwable err = ctx.error();
            span.setAttribute("error.type", err.getClass().getName());
            span.recordException(err);
            span.setStatus(StatusCode.ERROR, err.getMessage() == null ? "" : err.getMessage());
            recordDuration(span, ctx.attributes().get(START_KEY));
        } finally {
            span.end();
        }
    }

    private static void recordDuration(Span span, Object startNanos) {
        if (startNanos instanceof Long start) {
            span.setAttribute("gen_ai.client.duration_ms", (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static Span span(Object o) {
        return o instanceof Span s ? s : null;
    }

    /** ModelProvider 枚举名 → GenAI {@code gen_ai.system} 惯用小写值（OPEN_AI → openai）。 */
    private static String system(String providerName) {
        if (providerName == null) return "unknown";
        return switch (providerName) {
            case "OPEN_AI" -> "openai";
            case "ANTHROPIC" -> "anthropic";
            case "GOOGLE_AI_GEMINI", "GOOGLE_VERTEX_AI_GEMINI" -> "gemini";
            case "MISTRAL_AI" -> "mistral_ai";
            case "AMAZON_BEDROCK" -> "aws.bedrock";
            case "OLLAMA" -> "ollama";
            default -> providerName.toLowerCase();
        };
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
