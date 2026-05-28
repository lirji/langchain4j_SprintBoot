package com.lrj.langchain4j.security;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * 日 token 预算预检 filter。chat / extract / multi-agent / reflexive / eval 之类会触发 LLM
 * 调用的 endpoint，请求开始前看一眼 budget 是否还剩 —— 已超就 429 拦掉，省得真发起调用浪费成本。
 *
 * <p>不能精确预扣（不知道这次会用多少 token），用"超额即拒、本次仍允许 commit"的软限制策略。
 * 实际扣减由 {@code TokenBudgetChatModelListener} 在 onResponse 时做。
 *
 * <p>放在 {@link RateLimitFilter} 之后：先过 QPS，再看预算（顺序无强约束，但 QPS 拒绝更便宜，
 * 让它先 short-circuit）。
 */
public class TokenBudgetGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetGuardFilter.class);

    /** LLM-touching endpoint family —— 这些请求才需要预检。 */
    private static final Set<String> LLM_FAMILIES = Set.of("chat", "stream", "eval");

    private final TokenBudgetProperties props;
    private final TokenBudgetTracker tracker;
    private final AuditLogger audit;

    public TokenBudgetGuardFilter(TokenBudgetProperties props, TokenBudgetTracker tracker, AuditLogger audit) {
        this.props = props;
        this.tracker = tracker;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        String p = request.getServletPath();
        if (p.startsWith("/actuator") || "/health".equals(p)) return true;
        // 只拦 LLM-touching family；ingest / default 直接放行
        String family = RateLimitFilter.familyOf(p);
        return !LLM_FAMILIES.contains(family);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String tenantId = TenantContext.current().tenantId();
        long used = tracker.currentUsed(tenantId);
        long budget = props.resolveDailyBudget(tenantId);

        // 即便允许通过，也回写状态 header
        response.setHeader("X-Token-Budget-Limit", String.valueOf(budget));
        response.setHeader("X-Token-Budget-Used", String.valueOf(used));
        response.setHeader("X-Token-Budget-Remaining", String.valueOf(Math.max(0, budget - used)));

        if (used >= budget) {
            long wait = tracker.secondsUntilReset();
            response.setHeader("Retry-After", String.valueOf(wait));
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format(
                    "{\"error\":\"token_budget_exhausted\",\"tenant\":\"%s\",\"used\":%d,\"budget\":%d,\"retryAfterSeconds\":%d}",
                    tenantId, used, budget, wait));
            log.warn("token-budget exhausted tenant={} used={} budget={} path={} retryAfter={}s",
                    tenantId, used, budget, request.getServletPath(), wait);
            audit.record(AuditEventType.TOKEN_BUDGET_EXHAUSTED, Map.of(
                    "used", used,
                    "budget", budget,
                    "path", request.getServletPath(),
                    "retryAfterSeconds", wait));
            return;
        }

        chain.doFilter(request, response);
    }
}
