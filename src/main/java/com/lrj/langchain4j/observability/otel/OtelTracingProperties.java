package com.lrj.langchain4j.observability.otel;

/**
 * {@code app.observability.otel.*} 配置。用 OpenTelemetry GenAI 语义约定把一次 chat 请求
 * 记成一棵 span 树（CLIENT span，带 {@code gen_ai.*} 属性），补 Micrometer 指标之外的分布式追踪能力。
 *
 * <p>默认关闭 —— 关闭时不构造 OTel SDK / OTLP exporter，{@link OtelChatModelListener} 注入的是
 * no-op Tracer，onRequest/onResponse 走空实现，启动不失败、零开销。见 {@link OtelTracingConfig}。
 */
public class OtelTracingProperties {

    /** 采样策略。跟 OTel 标准 sampler 名对齐。 */
    public enum Sampler {
        /** 父 span 采样就采样，无父 span 时全采（默认，最常用）。 */
        PARENTBASED_ALWAYS_ON,
        /** 全采（本地 debug / 小流量）。 */
        ALWAYS_ON,
        /** 全不采（临时关追踪但保留装配）。 */
        ALWAYS_OFF,
        /** 按 {@code samplerRatio} 比例采（高流量降本）。 */
        TRACEIDRATIO
    }

    /** 总开关。默认关：不建 SDK / exporter，listener 走 no-op tracer。 */
    private boolean enabled = false;

    /** OTLP HTTP collector 端点（HTTP/protobuf）。默认本机 collector 的 HTTP 默认端口 4318。 */
    private String endpoint = "http://localhost:4318/v1/traces";

    /** span 归属的服务名（写进 resource 的 {@code service.name}）。 */
    private String serviceName = "langchain4j-app";

    /** 采样策略。 */
    private Sampler sampler = Sampler.PARENTBASED_ALWAYS_ON;

    /** {@code TRACEIDRATIO} 时的采样比例，[0.0, 1.0]。其余策略忽略。 */
    private double samplerRatio = 1.0;

    /** exporter 批量导出超时（毫秒）。 */
    private long exportTimeoutMs = 30_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Sampler getSampler() { return sampler; }
    public void setSampler(Sampler sampler) { this.sampler = sampler; }
    public double getSamplerRatio() { return samplerRatio; }
    public void setSamplerRatio(double samplerRatio) { this.samplerRatio = samplerRatio; }
    public long getExportTimeoutMs() { return exportTimeoutMs; }
    public void setExportTimeoutMs(long exportTimeoutMs) { this.exportTimeoutMs = exportTimeoutMs; }
}
