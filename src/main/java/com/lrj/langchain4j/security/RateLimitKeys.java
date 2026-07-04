package com.lrj.langchain4j.security;

/**
 * 「per-(tenant, family) 限流桶落 Redis」范式的共享纯函数 helper —— 桶 key 布局 + retry-after 换算。
 * 供 {@link RedisRateLimiterRegistry} 用，也让这两处逻辑可脱离 Redis 被 {@code RateLimitKeysTest} 确定性覆盖
 * （对齐 {@link RedisDailyCounters} 的「一处定义、纯函数可测」思路）。
 *
 * <p><strong>为何把 qpm 编进 key</strong>（{@code <prefix><tenant>|<family>|<qpm>}）：token bucket 的容量不可变，
 * yml 热更把某租户 QPM 一调，直接换新 key = 新桶（新满桶），无需显式失效旧桶 —— 与进程内 {@link InMemoryRateLimiterRegistry}
 * 把 qpm 编进 map key 的处理完全一致。旧 qpm 的 Redis key 靠 {@code PEXPIRE}（约 2× 窗口）自然过期，不会堆积
 * （比进程内版把历史桶永久留在 map 里更干净）。
 */
public final class RateLimitKeys {

    private RateLimitKeys() {}

    /** {@code <prefix><tenant>|<family>|<qpm>}，如 {@code rate:limit:acme|chat|60}。 */
    public static String bucketKey(String prefix, String tenantId, String family, int qpm) {
        return prefix + tenantId + "|" + family + "|" + qpm;
    }

    /** 毫秒等待 → Retry-After 秒（向上取整，被拒时至少回 1s，避免客户端 0 秒空转重试）。 */
    public static long retryAfterSeconds(long waitMillis) {
        if (waitMillis <= 0) return 1L;
        return Math.max(1L, (waitMillis + 999L) / 1000L);
    }
}
