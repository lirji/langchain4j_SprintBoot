package com.lrj.langchain4j.cost;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内 per-tenant 日 USD 成本累加器（默认后端）。与 {@link com.lrj.langchain4j.security.InMemoryTokenBudgetTracker}
 * 同构（日历日重置 + {@link AtomicReference#updateAndGet} 原子累加 + CHM 隔离），单位 token→USD。
 *
 * <p><strong>限单 JVM</strong>：多副本各持一份，同一租户成本分散在各 pod、看板需手动加总 ——
 * 要多 pod 汇总正确，切 {@code app.cost.store=redis}（{@link RedisCostTracker}）。
 */
public class InMemoryCostTracker implements CostTracker {

    private final String currency;
    private final Clock clock;
    private final ConcurrentMap<String, AtomicReference<Usage>> map = new ConcurrentHashMap<>();

    public InMemoryCostTracker(String timezone, String currency) {
        this(currency, Clock.system(resolveZone(timezone)));
    }

    /** 测试用：注入可控 {@link Clock} 以确定性验证跨日重置。 */
    InMemoryCostTracker(String currency, Clock clock) {
        this.currency = (currency == null || currency.isBlank()) ? "USD" : currency;
        this.clock = clock;
    }

    static ZoneId resolveZone(String timezone) {
        return (timezone == null || timezone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(timezone);
    }

    @Override
    public void record(String tenantId, double usd) {
        if (usd <= 0 || tenantId == null) return;
        AtomicReference<Usage> ref = ref(tenantId);
        LocalDate today = LocalDate.now(clock);
        ref.updateAndGet(u -> u.day.equals(today)
                ? new Usage(u.usd + usd, u.day)
                : new Usage(usd, today));
    }

    @Override
    public double currentUsd(String tenantId) {
        Usage u = ref(tenantId).get();
        LocalDate today = LocalDate.now(clock);
        return u.day.equals(today) ? u.usd : 0.0;
    }

    @Override
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);
        for (Map.Entry<String, AtomicReference<Usage>> e : map.entrySet()) {
            Usage u = e.getValue().get();
            double usd = u.day.equals(today) ? u.usd : 0.0;
            out.put(e.getKey(), new Snapshot(round(usd), currency, today.toString()));
        }
        return out;
    }

    private AtomicReference<Usage> ref(String tenantId) {
        return map.computeIfAbsent(tenantId,
                k -> new AtomicReference<>(new Usage(0.0, LocalDate.now(clock))));
    }

    static double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0; // 6 位小数，够表达 μ$ 级别单次调用
    }

    private record Usage(double usd, LocalDate day) {}
}
