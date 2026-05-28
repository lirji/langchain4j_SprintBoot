package com.lrj.langchain4j.security;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内 per-tenant token 预算计数器。日历日重置 —— 比较当前日期跟桶里记录的 day，
 * 不同就 reset 为 0。
 *
 * <p>用 {@link AtomicReference} 持有 immutable {@link Usage} record，{@link AtomicReference#updateAndGet}
 * 保证并发 consume 时的原子性。CHM 本身保证不同 tenant 互不干扰。
 *
 * <p>多实例部署：把 map 换成 Redis（{@code INCRBY tenant:tokens:YYYY-MM-DD} + EXPIREAT 次日 0 点），
 * 业务接口（{@link #consume} / {@link #wouldExceed} / {@link #currentUsed}）保持不变。
 */
@Component
public class TokenBudgetTracker {

    private final TokenBudgetProperties props;
    private final ZoneId zone;
    private final ConcurrentMap<String, AtomicReference<Usage>> map = new ConcurrentHashMap<>();

    public TokenBudgetTracker(TokenBudgetProperties props) {
        this.props = props;
        this.zone = (props.getTimezone() == null || props.getTimezone().isBlank())
                ? ZoneId.systemDefault()
                : ZoneId.of(props.getTimezone());
    }

    /** 当前 tenant 今天已用 token 数（自动 reset 若已跨日）。 */
    public long currentUsed(String tenantId) {
        return snapshot(tenantId).used;
    }

    /** 当前 tenant 是否已经用满（>=）今日预算；用于请求前预检。 */
    public boolean wouldExceed(String tenantId) {
        return snapshot(tenantId).used >= props.resolveDailyBudget(tenantId);
    }

    /** 给当前 tenant 累加 token；listener.onResponse 调。 */
    public void consume(String tenantId, long tokens) {
        if (tokens <= 0) return;
        AtomicReference<Usage> ref = ref(tenantId);
        LocalDate today = LocalDate.now(zone);
        ref.updateAndGet(u -> u.day.equals(today)
                ? new Usage(u.used + tokens, u.day)
                : new Usage(tokens, today));
    }

    /** 到次日 0 点的秒数；429 响应的 Retry-After header 用。 */
    public long secondsUntilReset() {
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime midnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
        long secs = java.time.Duration.between(now, midnight).getSeconds();
        return Math.max(1L, secs);
    }

    /** Actuator 端点用：拷贝一份给运维看（按 tenant 列出今日 used / budget）。 */
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> out = new java.util.LinkedHashMap<>();
        LocalDate today = LocalDate.now(zone);
        for (Map.Entry<String, AtomicReference<Usage>> e : map.entrySet()) {
            Usage u = e.getValue().get();
            long used = u.day.equals(today) ? u.used : 0L;
            long budget = props.resolveDailyBudget(e.getKey());
            out.put(e.getKey(), new Snapshot(used, budget, today.toString()));
        }
        return out;
    }

    private Usage snapshot(String tenantId) {
        Usage u = ref(tenantId).get();
        LocalDate today = LocalDate.now(zone);
        return u.day.equals(today) ? u : new Usage(0L, today);
    }

    private AtomicReference<Usage> ref(String tenantId) {
        return map.computeIfAbsent(tenantId,
                k -> new AtomicReference<>(new Usage(0L, LocalDate.now(zone))));
    }

    private record Usage(long used, LocalDate day) {}

    public record Snapshot(long used, long budget, String day) {}
}
