package com.lrj.langchain4j.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测「per-tenant 日计数落 Redis」范式的共享纯函数 helper {@link RedisDailyCounters} ——
 * key 布局 / 租户解析 / 次日午夜 epoch。不连 Redis；Redis 往返（Lua INCRBY(FLOAT)+PEXPIREAT / SCAN）
 * 属集成，靠真 Redis 起服务验证，见 docs/distributed-state.md「怎么跑」。
 * {@link RedisTokenBudgetTracker}（token）与 {@link com.lrj.langchain4j.cost.RedisCostTracker}（USD）共用这套。
 */
class RedisDailyCountersTest {

    @Test
    void dayKey_embedsDateThenTenant() {
        assertThat(RedisDailyCounters.dayKey("token:budget:", LocalDate.parse("2026-07-04"), "acme"))
                .isEqualTo("token:budget:2026-07-04:acme");
    }

    @Test
    void scanPrefix_isPrefixPlusDateColon() {
        assertThat(RedisDailyCounters.scanPrefix("cost:usd:", LocalDate.parse("2026-07-04")))
                .isEqualTo("cost:usd:2026-07-04:");
    }

    @Test
    void tenantFromKey_stripsFullPrefix() {
        String fullPrefix = "token:budget:2026-07-04:";
        assertThat(RedisDailyCounters.tenantFromKey(fullPrefix, fullPrefix + "acme")).isEqualTo("acme");
    }

    @Test
    void tenantFromKey_preservesColonsInTenantId() {
        // tenantId 含 ':' 也不该被截断（date 在前、tenant 在末段的布局保证了这点）
        String fullPrefix = "token:budget:2026-07-04:";
        assertThat(RedisDailyCounters.tenantFromKey(fullPrefix, fullPrefix + "org:team:a"))
                .isEqualTo("org:team:a");
    }

    @Test
    void tenantFromKey_nonMatchingOrEmpty_returnsNull() {
        String fullPrefix = "token:budget:2026-07-04:";
        assertThat(RedisDailyCounters.tenantFromKey(fullPrefix, "other:key")).isNull();
        assertThat(RedisDailyCounters.tenantFromKey(fullPrefix, fullPrefix)).isNull(); // 无 tenant 段
        assertThat(RedisDailyCounters.tenantFromKey(fullPrefix, null)).isNull();
    }

    @Test
    void nextMidnightMillis_isStartOfTomorrowInZone() {
        // 2026-07-04T10:00Z → 次日午夜 = 2026-07-05T00:00Z
        Clock clock = Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC);
        long expected = LocalDate.parse("2026-07-05").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertThat(RedisDailyCounters.nextMidnightMillis(clock)).isEqualTo(expected);
    }

    @Test
    void nextMidnightMillis_honorsClockZone() {
        // 同一瞬间，上海时区（+08）的"次日午夜"跟 UTC 的不同，且必在当前之后
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), sh);
        long expected = LocalDate.now(clock).plusDays(1).atStartOfDay(sh).toInstant().toEpochMilli();
        assertThat(RedisDailyCounters.nextMidnightMillis(clock)).isEqualTo(expected);
        assertThat(RedisDailyCounters.nextMidnightMillis(clock)).isGreaterThan(clock.millis());
    }
}
