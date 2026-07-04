package com.lrj.langchain4j.security;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 「per-tenant 日计数落 Redis」范式的共享纯函数 helper —— key 布局 / 租户解析 / 次日午夜过期时刻。
 * {@link RedisTokenBudgetTracker}（token 用量，{@code INCRBY}）与 {@link com.lrj.langchain4j.cost.RedisCostTracker}
 * （USD 成本，{@code INCRBYFLOAT}）共用，把这套范式从"复制粘贴"变成"一处定义、多处复用"。
 *
 * <p><strong>key 布局</strong> {@code <prefix><date>:<tenantId>}（date 在前、tenantId 在末段）：
 * <ul>
 *   <li>date 内嵌 → 跨日自然换 key、旧 key 到 {@link #nextMidnightMillis} 自动过期，<strong>无需定时清理</strong>；</li>
 *   <li>tenantId 在末段 → {@code SCAN <prefix><today>:*} 枚举后按定长前缀切出 tenantId，
 *       tenantId 含 {@code :} 也不误切。</li>
 * </ul>
 */
public final class RedisDailyCounters {

    private RedisDailyCounters() {}

    /** {@code <prefix><date>:<tenantId>}，如 {@code token:budget:2026-07-04:acme}。 */
    public static String dayKey(String prefix, LocalDate date, String tenantId) {
        return prefix + date + ":" + tenantId;
    }

    /** {@code snapshotAll} 用的完整前缀 {@code <prefix><date>:}（SCAN pattern = 此 + {@code *}）。 */
    public static String scanPrefix(String prefix, LocalDate date) {
        return prefix + date + ":";
    }

    /** 从扫描回的完整 key 切出 tenantId（{@code fullPrefix} = {@link #scanPrefix}）；不匹配/无 tenant 段返回 null。 */
    public static String tenantFromKey(String fullPrefix, String key) {
        if (key == null || !key.startsWith(fullPrefix) || key.length() <= fullPrefix.length()) return null;
        return key.substring(fullPrefix.length());
    }

    /** {@code clock} 时区下"次日 0 点"的 epoch millis —— key 的绝对过期时刻（PEXPIREAT）。 */
    public static long nextMidnightMillis(Clock clock) {
        ZoneId zone = clock.getZone();
        return LocalDate.now(clock).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
