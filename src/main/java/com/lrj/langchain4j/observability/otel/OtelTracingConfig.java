package com.lrj.langchain4j.observability.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OpenTelemetry GenAI 分布式追踪装配。
 *
 * <p>设计跟本仓库其它 feature 一致：<strong>默认关</strong>（{@code app.observability.otel.enabled=false}）。
 * 但装配方式跟纯 config 条件化略有不同 —— {@link OtelChatModelListener} 是无条件的 {@code @Component}
 * （要能一直加入 {@code List<ChatModelListener>}），它构造需要一个 {@link Tracer}。所以这里始终提供 Tracer：
 * <ul>
 *   <li>开启：{@link #openTelemetry} 建 SDK（OTLP HTTP exporter + resource + sampler），{@link #otelTracer} 从中取真实 tracer；</li>
 *   <li>关闭：SDK / 真实 tracer 都不建，{@link #noopTracer} 兜底给一个 no-op tracer —— listener 照常注入，span 全是空操作，启动不失败、零开销。</li>
 * </ul>
 */
@Configuration
public class OtelTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelTracingConfig.class);

    private static final String INSTRUMENTATION_SCOPE = "com.lrj.langchain4j.observability.otel";

    @Bean
    @ConfigurationProperties(prefix = "app.observability.otel")
    public OtelTracingProperties otelTracingProperties() {
        return new OtelTracingProperties();
    }

    /**
     * OTel SDK —— 仅开启时构造。用 OTLP HTTP exporter 把 span 批量导到 collector
     * （HTTP/protobuf，走 OkHttp/JDK，不引 grpc —— 避开本仓库为 Milvus 钉死的 grpc 1.59.1 版本冲突）。
     * close 由 Spring 销毁时触发，保证退出前 flush。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.observability.otel.enabled", havingValue = "true")
    public OpenTelemetrySdk openTelemetry(OtelTracingProperties props) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(props.getEndpoint())
                .setTimeout(Duration.ofMillis(props.getExportTimeoutMs()))
                .build();

        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), props.getServiceName())));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(toSampler(props))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        log.info("OpenTelemetry GenAI tracing 已开启 endpoint={} service={} sampler={}",
                props.getEndpoint(), props.getServiceName(), props.getSampler());

        // 不注册成全局（GlobalOpenTelemetry），避免多实例/测试互相污染；tracer 直接从这个实例取。
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.observability.otel.enabled", havingValue = "true")
    public Tracer otelTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * 兜底 no-op tracer —— 只在没有真实 tracer 时生效（即关闭态）。让 {@link OtelChatModelListener}
     * 永远能注入到 Tracer，span 走空操作，不用自己判开关、启动不失败。
     */
    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    public Tracer noopTracer() {
        return OpenTelemetry.noop().getTracer(INSTRUMENTATION_SCOPE);
    }

    private static Sampler toSampler(OtelTracingProperties props) {
        return switch (props.getSampler()) {
            case ALWAYS_ON -> Sampler.alwaysOn();
            case ALWAYS_OFF -> Sampler.alwaysOff();
            case TRACEIDRATIO -> Sampler.traceIdRatioBased(props.getSamplerRatio());
            case PARENTBASED_ALWAYS_ON -> Sampler.parentBased(Sampler.alwaysOn());
        };
    }
}
