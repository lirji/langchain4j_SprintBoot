package com.lrj.langchain4j.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 {@link InMemoryTokenBudgetTracker} 的确定性行为：累加 / 跨日重置（注入可控 Clock）/
 * wouldExceed 阈值 / anonymous 倍率 / snapshot / 非正忽略。
 */
class InMemoryTokenBudgetTrackerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private TokenBudgetProperties props(long dailyDefault) {
        TokenBudgetProperties p = new TokenBudgetProperties();
        p.setTimezone("UTC");
        p.getDailyTokens().setDefault(dailyDefault);
        p.setAnonymousMultiplier(0.05);
        return p;
    }

    @Test
    void consume_accumulatesWithinDay() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryTokenBudgetTracker(props(1000), clock);
        t.consume("acme", 300);
        t.consume("acme", 200);
        assertThat(t.currentUsed("acme")).isEqualTo(500);
    }

    @Test
    void wouldExceed_atOrAboveBudget() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryTokenBudgetTracker(props(1000), clock);
        t.consume("acme", 999);
        assertThat(t.wouldExceed("acme")).isFalse();
        t.consume("acme", 1);
        assertThat(t.wouldExceed("acme")).isTrue(); // 1000 >= 1000
    }

    @Test
    void crossingMidnight_resetsCounter() {
        var clock = new MutableClock(Instant.parse("2026-07-04T23:00:00Z"), UTC);
        var t = new InMemoryTokenBudgetTracker(props(1000), clock);
        t.consume("acme", 800);
        assertThat(t.currentUsed("acme")).isEqualTo(800);
        clock.advance(Duration.ofHours(2)); // → 2026-07-05T01:00Z，新的一天
        assertThat(t.currentUsed("acme")).isEqualTo(0);
        t.consume("acme", 100);
        assertThat(t.currentUsed("acme")).isEqualTo(100);
    }

    @Test
    void anonymousTenant_getsReducedBudget() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryTokenBudgetTracker(props(1000), clock);
        // anonymous 预算 = floor(1000 * 0.05) = 50
        t.consume("anonymous", 50);
        assertThat(t.wouldExceed("anonymous")).isTrue();
        assertThat(t.wouldExceed("acme")).isFalse(); // 普通租户 1000 未满
    }

    @Test
    void snapshotAll_listsPerTenantUsedAndBudget() {
        var clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC);
        var t = new InMemoryTokenBudgetTracker(props(1000), clock);
        t.consume("acme", 300);
        t.consume("beta", 100);
        var snap = t.snapshotAll();
        assertThat(snap).containsKeys("acme", "beta");
        assertThat(snap.get("acme").used()).isEqualTo(300);
        assertThat(snap.get("acme").budget()).isEqualTo(1000);
        assertThat(snap.get("acme").day()).isEqualTo("2026-07-04");
    }

    @Test
    void nonPositiveConsume_ignored() {
        var t = new InMemoryTokenBudgetTracker(props(1000),
                new MutableClock(Instant.parse("2026-07-04T10:00:00Z"), UTC));
        t.consume("acme", 0);
        t.consume("acme", -5);
        assertThat(t.currentUsed("acme")).isEqualTo(0);
    }

    @Test
    void secondsUntilReset_positiveAndBoundedByDay() {
        var t = new InMemoryTokenBudgetTracker(props(1000),
                new MutableClock(Instant.parse("2026-07-04T23:59:00Z"), UTC));
        long secs = t.secondsUntilReset();
        assertThat(secs).isBetween(1L, 60L); // 距次日 0 点 1 分钟内
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
