package com.lrj.langchain4j.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 token-bucket 限流器（默认后端）。key 是 {@code tenantId|family|qpm}，value 是 Bucket4j {@link Bucket}：
 * 每分钟贪婪补满 N 个 token、burst capacity 也是 N（不允许超发）。
 *
 * <p><strong>限单 JVM</strong>：多副本部署时每个进程各持一份桶，同一租户的 QPM 被放大到副本数倍 ——
 * 要在水平扩容下正确限流，切 {@code app.rate-limit.store=redis}（{@link RedisRateLimiterRegistry}）。
 *
 * <p>限额变更（yml 热更）通过 {@link RateLimitProperties#resolveQpm} 拿到，但 Bucket4j 桶容量不可变，
 * 故把 effective qpm 编进 cache key（{@code tenantId|family|qpm}）—— qpm 变了就是新桶，避免显式失效逻辑；
 * 代价是历史 qpm 桶留在 map 里，但 tenant × family × qpm 基数有限，可忽略。
 */
public class InMemoryRateLimiterRegistry implements RateLimiterRegistry {

    private final RateLimitProperties props;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiterRegistry(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public Decision tryConsume(String tenantId, String family) {
        int qpm = props.resolveQpm(tenantId, family);
        String key = RateLimitKeys.bucketKey("", tenantId, family, qpm);
        Bucket bucket = buckets.computeIfAbsent(key, k -> buildBucket(qpm));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long remaining = Math.max(0, probe.getRemainingTokens());
        if (probe.isConsumed()) {
            return new Decision(true, remaining, 0, qpm);
        }
        long waitMillis = probe.getNanosToWaitForRefill() / 1_000_000L;
        return new Decision(false, remaining, RateLimitKeys.retryAfterSeconds(waitMillis), qpm);
    }

    private static Bucket buildBucket(int qpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(qpm)
                .refillGreedy(qpm, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
