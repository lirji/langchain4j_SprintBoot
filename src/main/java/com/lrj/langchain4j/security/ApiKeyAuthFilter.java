package com.lrj.langchain4j.security;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验 {@code X-Api-Key} header，把对应租户绑定到 {@link TenantContext}、{@link SecurityContextHolder}
 * 和 MDC（{@code tenantId}/{@code userId}，方便日志聚合）。
 *
 * <p>放在 {@code UsernamePasswordAuthenticationFilter} 之前；放行规则在 {@code SecurityConfig}。
 *
 * <p>未带 header / key 不匹配 → 直接 401（{@code SecurityConfig.anyRequest().authenticated()} 会
 * 拒绝；本 filter 不显式写 401 是为了让 actuator/health 这类放行路径还能正常走）。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String MDC_TENANT = "tenantId";
    public static final String MDC_USER = "userId";

    private final SecurityProperties props;
    private final AuditLogger audit;

    public ApiKeyAuthFilter(SecurityProperties props, AuditLogger audit) {
        this.props = props;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        SecurityProperties.KeyBinding binding = (apiKey == null) ? null : props.getApiKeys().get(apiKey);

        if (binding == null && apiKey != null) {
            // 提供了 key 但不匹配 —— 单独 audit，区别于"完全没带 key"
            audit.record(AuditEventType.AUTH_DENIED, Map.of(
                    "path", request.getServletPath(),
                    "reason", "invalid_api_key"));
        }

        if (binding != null) {
            Set<String> scopes = binding.getScopes() == null
                    ? Set.of()
                    : new HashSet<>(binding.getScopes());
            TenantContext.Tenant tenant = new TenantContext.Tenant(
                    binding.getTenant(), binding.getUser(), scopes);
            TenantContext.set(tenant);
            MDC.put(MDC_TENANT, binding.getTenant());
            MDC.put(MDC_USER, binding.getUser());

            Collection<SimpleGrantedAuthority> authorities = scopes.stream()
                    .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new ApiKeyAuthentication(binding.getUser(), authorities));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_USER);
            SecurityContextHolder.clearContext();
        }
    }

    /** 简单的已认证 token，principal 就是 userId，credentials 不暴露 api-key。 */
    static class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final String principal;

        ApiKeyAuthentication(String principal, Collection<SimpleGrantedAuthority> authorities) {
            super(authorities == null ? List.of() : authorities);
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return principal; }
    }
}
