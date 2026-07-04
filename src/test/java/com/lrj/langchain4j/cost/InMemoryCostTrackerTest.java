package com.lrj.langchain4j.cost;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 测 {@link InMemoryCostTracker} 的确定性行为：累加 / 跨日重置（注入可控 Clock）/ snapshot /
 * 非正忽略 / 6 位小数归整。
 */
class InMemoryCostTrackerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final double EPS = 1e-9;

    @Test
    void record_accumulatesWithinDay() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryCostTracker("USD", clock);
        t.record("acme", 0.0003);
        t.record("acme", 0.0007);
        assertThat(t.currentUsd("acme")).isCloseTo(0.001, offset(EPS));
    }

    @Test
    void crossingMidnight_resetsCost() {
        var clock = new MutableClock(Instant.parse("2026-07-04T23:00:00Z"), UTC);
        var t = new InMemoryCostTracker("USD", clock);
        t.record("acme", 1.50);
        assertThat(t.currentUsd("acme")).isCloseTo(1.50, offset(EPS));
        clock.advance(Duration.ofHours(2)); // → 次日
        assertThat(t.currentUsd("acme")).isEqualTo(0.0);
        t.record("acme", 0.25);
        assertThat(t.currentUsd("acme")).isCloseTo(0.25, offset(EPS));
    }

    @Test
    void snapshotAll_perTenantWithCurrencyAndDay() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryCostTracker("USD", clock);
        t.record("acme", 0.42);
        t.record("beta", 0.10);
        var snap = t.snapshotAll();
        assertThat(snap).containsKeys("acme", "beta");
        assertThat(snap.get("acme").usd()).isCloseTo(0.42, offset(EPS));
        assertThat(snap.get("acme").currency()).isEqualTo("USD");
        assertThat(snap.get("acme").day()).isEqualTo("2026-07-04");
    }

    @Test
    void nonPositive_ignored() {
        var t = new InMemoryCostTracker("USD",
                new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC));
        t.record("acme", 0.0);
        t.record("acme", -0.5);
        assertThat(t.currentUsd("acme")).isEqualTo(0.0);
    }

    @Test
    void snapshot_roundsToMicroDollars() {
        var t = new InMemoryCostTracker("USD",
                new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC));
        t.record("acme", 0.123456789); // 归整到 6 位 → 0.123457
        assertThat(t.snapshotAll().get("acme").usd()).isEqualTo(0.123457);
    }

    @Test
    void nullCurrency_defaultsToUsd() {
        var t = new InMemoryCostTracker(null,
                new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC));
        t.record("acme", 0.10);
        assertThat(t.snapshotAll().get("acme").currency()).isEqualTo("USD");
    }

    /** 可推进的测试 Clock，让同一 tracker 实例看到时间流逝（跨日）。 */
    static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }
        @Override public Instant instant() { return instant; }
        void advance(Duration d) { this.instant = instant.plus(d); }
    }
}
