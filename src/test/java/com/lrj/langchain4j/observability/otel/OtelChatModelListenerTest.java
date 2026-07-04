package com.lrj.langchain4j.observability.otel;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OtelChatModelListener 的确定性行为：把一次 chat 调用记成带 GenAI 属性的 CLIENT span。
 * 用 OTel SDK 的 InMemorySpanExporter 断言导出的 span，不连模型 / collector / 网络。
 */
class OtelChatModelListenerTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OtelChatModelListener listener;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
                .getTracer("test");
        listener = new OtelChatModelListener(tracer);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        TenantContext.clear();
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder()
                .messages(UserMessage.from("你好"))
                .modelName(model)
                .build();
    }

    @Test
    void emitsClientSpanWithGenAiAttributesOnResponse() {
        TenantContext.set(new TenantContext.Tenant("acme", "u-1", Set.of()));
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(
                request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        // span 还没结束，尚未导出
        assertThat(exporter.getFinishedSpanItems()).isEmpty();

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("你好，有什么可以帮你？"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("gpt-4o-mini")
                        .tokenUsage(new TokenUsage(12, 8))
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        listener.onResponse(new ChatModelResponseContext(
                response, request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);

        assertThat(span.getName()).isEqualTo("chat gpt-4o-mini");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusData.ok().getStatusCode());

        var a = span.getAttributes();
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("chat");
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.system"))).isEqualTo("openai");
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(12L);
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(8L);
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.response.finish_reasons"))).isEqualTo("STOP");
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("tenant.id"))).isEqualTo("acme");
        assertThat(a.get(io.opentelemetry.api.common.AttributeKey.stringKey("enduser.id"))).isEqualTo("u-1");
    }

    @Test
    void emitsErrorSpanOnError() {
        Map<Object, Object> attrs = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(
                request("llama3.1"), ModelProvider.OLLAMA, attrs));

        listener.onError(new ChatModelErrorContext(
                new RuntimeException("boom"), request("llama3.1"), ModelProvider.OLLAMA, attrs));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusData.error().getStatusCode());
        assertThat(span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("error.type")))
                .isEqualTo("java.lang.RuntimeException");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void noopTracerEmitsNothing() {
        // 关闭态：listener 注入 no-op tracer，span 全空操作、不导出
        OtelChatModelListener noop = new OtelChatModelListener(
                io.opentelemetry.api.OpenTelemetry.noop().getTracer("noop"));
        Map<Object, Object> attrs = new HashMap<>();
        noop.onRequest(new ChatModelRequestContext(
                request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("hi"))
                .metadata(ChatResponseMetadata.builder().modelName("gpt-4o-mini").build())
                .build();
        noop.onResponse(new ChatModelResponseContext(
                response, request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
