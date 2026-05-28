package com.lrj.langchain4j.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 token-bucket 注册表。key 是 {@code tenantId|family}，value 是 {@link Bucket}。
 *
 * <p>用 Bucket4j 的内存桶：每分钟补满 N 个 token、burst capacity 也是 N（不允许超发）。
 * 多实例部署要换成 Bucket4j 的 distributed proxy（Redis / Hazelcast），
 * 把 {@link #buildBucket} 换成 {@code proxyManager.builder().build(key, config)} 即可，
 * filter / properties / 调用方都不动。
 *
 * <p>限额变更（yml 热更）会通过 {@code resolveCurrent()} 拿到，但 Bucket4j 的桶容量是
 * 不可变的，所以当限额变化时直接 {@code computeIfAbsent} 取的旧桶不会 rebuild。
 * 简化处理：把 effective qpm 编进 cache key（{@code tenantId|family|qpm}），qpm 变了就是新桶。
 * 这避免了显式失效逻辑；代价是历史 qpm 桶留在 map 里，但 tenant × family × qpm 基数有限，可忽略。
 */
@Component
public class RateLimiterRegistry {

    private final RateLimitProperties props;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterRegistry(RateLimitProperties props) {
        this.props = props;
    }

    public Bucket bucketFor(String tenantId, String family) {
        int qpm = props.resolveQpm(tenantId, family);
        String key = tenantId + "|" + family + "|" + qpm;
        return buckets.computeIfAbsent(key, k -> buildBucket(qpm));
    }

    private static Bucket buildBucket(int qpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(qpm)
                .refillGreedy(qpm, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
