package com.lrj.langchain4j.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计事件统一入口。一行 JSON 写到名为 {@code AUDIT} 的 SLF4J logger —— logback-spring.xml
 * 把它单独路由到 {@code logs/audit.jsonl}（按日轮转），不污染主 console。生产用
 * Filebeat / Vector / Fluent Bit 自动采集到 ELK / Loki / S3 即可，不必引入 Kafka。
 *
 * <p>固定字段：
 * <ul>
 *   <li>{@code ts}     — ISO-8601 instant，UTC</li>
 *   <li>{@code type}   — 见 {@link AuditEventType}</li>
 *   <li>{@code traceId} — 从 MDC 取（{@code TraceIdFilter} 注入）</li>
 *   <li>{@code tenantId} / {@code userId} — 从 {@link TenantContext} 取</li>
 * </ul>
 * 业务字段通过 {@link #record(AuditEventType, Map)} 透传，落到顶层 JSON。
 *
 * <p><strong>零拖累原则</strong>：序列化或写日志失败时 catch 吞掉 + meta-log
 * （记到主 logger）。审计不能拖垮业务。
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger META = LoggerFactory.getLogger(AuditLogger.class);

    private final ObjectMapper mapper;

    public AuditLogger(ObjectMapper mapper) {
        // Spring Boot 已经装 ObjectMapper Bean，复用 —— 跟 controller 的 JSON 序列化口径一致
        this.mapper = mapper;
    }

    public void record(AuditEventType type, Map<String, Object> fields) {
        try {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("ts", Instant.now().toString());
            evt.put("type", type.wire());
            evt.put("traceId", MDC.get("traceId"));
            TenantContext.Tenant t = TenantContext.current();
            evt.put("tenantId", t.tenantId());
            evt.put("userId", t.userId());
            if (fields != null) evt.putAll(fields);
            AUDIT.info(mapper.writeValueAsString(evt));
        } catch (Throwable ex) {
            META.warn("audit emit failed type={}: {}", type, ex.toString());
        }
    }
}
