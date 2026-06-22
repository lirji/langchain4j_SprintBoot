package com.lrj.langchain4j.security;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Per-(tenant, endpoint family) 限流。把 URL 路径映射到 family，从 {@link RateLimiterRegistry}
 * 取桶，尝试消费 1 个 token；不够直接 429 + {@code Retry-After}（秒）。
 *
 * <p>装在 {@code ApiKeyAuthFilter} 之后（需要 {@link TenantContext}），actuator/health
 * 等放行路径仍然走 {@code SecurityConfig} 跳过 auth chain，所以这个 filter 不会拦到它们。
 * 但 filter 实例对所有 servlet 请求都生效，所以这里也用 {@link #shouldNotFilter} 双保险跳过。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties props;
    private final RateLimiterRegistry registry;
    private final AuditLogger audit;

    public RateLimitFilter(RateLimitProperties props, RateLimiterRegistry registry, AuditLogger audit) {
        this.props = props;
        this.registry = registry;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        String p = request.getServletPath();
        return p.startsWith("/actuator") || "/health".equals(p);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String tenantId = TenantContext.current().tenantId();
        String family = familyOf(request.getServletPath());
        Bucket bucket = registry.bucketFor(tenantId, family);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        // 即便允许通过，也回写当前桶剩余 token + 限额，方便客户端做退避
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
        response.setHeader("X-RateLimit-Limit", String.valueOf(props.resolveQpm(tenantId, family)));

        if (!probe.isConsumed()) {
            long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            response.setHeader("Retry-After", String.valueOf(waitSeconds));
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format(
                    "{\"error\":\"rate_limited\",\"family\":\"%s\",\"tenant\":\"%s\",\"retryAfterSeconds\":%d}",
                    family, tenantId, waitSeconds));
            log.warn("rate-limited tenant={} family={} path={} retryAfter={}s",
                    tenantId, family, request.getServletPath(), waitSeconds);
            audit.record(AuditEventType.RATE_LIMITED, Map.of(
                    "family", family,
                    "path", request.getServletPath(),
                    "retryAfterSeconds", waitSeconds,
                    "limit", props.resolveQpm(tenantId, family)));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * URL 路径 → endpoint family。匹配顺序敏感：更具体的（/chat/stream）必须在更宽的（/chat）之前。
     * 没匹配上 → {@code default}。
     */
    static String familyOf(String path) {
        if (path == null) return "default";
        if (path.endsWith("/stream")) return "stream";              // /chat/stream, /chat/reflexive/stream, /chat/multi-agent/stream
        if (path.startsWith("/rag/ingest")) return "ingest";
        if (path.startsWith("/eval/")) return "eval";
        if (path.startsWith("/a2a")) return "a2a";                  // A2A JSON-RPC（message/send 同步、tasks/* 管理）
        if (path.startsWith("/chat") || path.startsWith("/extract")) return "chat";
        return "default";
    }
}
