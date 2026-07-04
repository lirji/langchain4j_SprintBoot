package com.lrj.langchain4j.security;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内 per-tenant token 预算计数器（默认后端）。日历日重置 —— 比较当前日期跟桶里记录的 day，
 * 不同就 reset 为 0。用 {@link AtomicReference#updateAndGet} 保证并发 consume 原子，CHM 隔离租户。
 *
 * <p><strong>限单 JVM</strong>：多副本部署时每个进程各持一份 map，同一租户的配额会被放大到副本数倍 ——
 * 要在水平扩容下正确限额，切 {@code app.token-budget.store=redis}（{@link RedisTokenBudgetTracker}）。
 */
public class InMemoryTokenBudgetTracker implements TokenBudgetTracker {

    private final TokenBudgetProperties props;
    private final Clock clock;
    private final ConcurrentMap<String, AtomicReference<Usage>> map = new ConcurrentHashMap<>();

    public InMemoryTokenBudgetTracker(TokenBudgetProperties props) {
        this(props, Clock.system(resolveZone(props)));
    }

    /** 测试用：注入可控 {@link Clock} 以确定性验证跨日重置。 */
    InMemoryTokenBudgetTracker(TokenBudgetProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    static ZoneId resolveZone(TokenBudgetProperties props) {
        return (props.getTimezone() == null || props.getTimezone().isBlank())
                ? ZoneId.systemDefault()
                : ZoneId.of(props.getTimezone());
    }

    @Override
    public long currentUsed(String tenantId) {
        return snapshot(tenantId).used;
    }

    @Override
    public boolean wouldExceed(String tenantId) {
        return snapshot(tenantId).used >= props.resolveDailyBudget(tenantId);
    }

    @Override
    public void consume(String tenantId, long tokens) {
        if (tokens <= 0) return;
        AtomicReference<Usage> ref = ref(tenantId);
        LocalDate today = LocalDate.now(clock);
        ref.updateAndGet(u -> u.day.equals(today)
                ? new Usage(u.used + tokens, u.day)
                : new Usage(tokens, today));
    }

    @Override
    public long secondsUntilReset() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime midnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
        long secs = java.time.Duration.between(now, midnight).getSeconds();
        return Math.max(1L, secs);
    }

    @Override
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);
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
        LocalDate today = LocalDate.now(clock);
        return u.day.equals(today) ? u : new Usage(0L, today);
    }

    private AtomicReference<Usage> ref(String tenantId) {
        return map.computeIfAbsent(tenantId,
                k -> new AtomicReference<>(new Usage(0L, LocalDate.now(clock))));
    }

    private record Usage(long used, LocalDate day) {}
}
