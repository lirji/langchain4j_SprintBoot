package com.lrj.langchain4j.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

/**
 * Redis-backed token-bucket 限流器 —— 让 QPM 在<strong>多副本 / 多 pod</strong> 下真正生效
 * （进程内的 {@link InMemoryRateLimiterRegistry} 每个副本各持一桶，等于把限额放大到副本数倍）。
 *
 * <p>桶状态（{@code tokens} 剩余 + {@code ts} 上次补桶时刻）存在一个 Redis Hash 里，每次 {@link #tryConsume}
 * 走一段 Lua：按 {@code (now - ts)} 贪婪补桶（连续补，语义对齐 Bucket4j 的 {@code refillGreedy}）→ 够则扣 1、
 * 不够算出到下一个 token 的等待毫秒 → 回写 + {@code PEXPIRE}（约 2× 窗口，闲置桶自动回收）。整段服务端<strong>原子</strong>执行，
 * 多 pod 并发不丢桶、不超发。
 *
 * <p>key 布局 {@code <prefix><tenant>|<family>|<qpm>}（见 {@link RateLimitKeys}）：qpm 编进 key，yml 调限额即换新满桶，
 * 无需显式失效。<strong>fail-open</strong>：Redis 抖动/不可达时放行（限流是流量整形、不是安全边界，
 * 不该因缓存故障把用户全拒掉；真安全拦截由 auth 兜底）。
 */
public class RedisRateLimiterRegistry implements RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterRegistry.class);

    private static final long WINDOW_MS = 60_000L;

    /**
     * 原子 token bucket：贪婪补桶 → 尝试消费 1 → 回写 + 过期。
     * 返回 {@code {allowed, floor(remaining), waitMillis}}（Lua 表 → List&lt;Long&gt;）。
     * 注：桶内 {@code tokens} 用浮点存（HSET 转字符串保留小数），只在返回时 {@code math.floor} 成整数。
     */
    private static final RedisScript<List> BUCKET = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1])\n" +
            "local capacity = tonumber(ARGV[2])\n" +
            "local windowMs = tonumber(ARGV[3])\n" +
            "local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')\n" +
            "local tokens = tonumber(data[1])\n" +
            "local ts = tonumber(data[2])\n" +
            "if tokens == nil then tokens = capacity; ts = now end\n" +
            "local elapsed = now - ts\n" +
            "if elapsed < 0 then elapsed = 0 end\n" +
            "tokens = math.min(capacity, tokens + elapsed * capacity / windowMs)\n" +
            "local allowed = 0\n" +
            "local waitMs = 0\n" +
            "if tokens >= 1 then\n" +
            "  tokens = tokens - 1\n" +
            "  allowed = 1\n" +
            "else\n" +
            "  waitMs = math.ceil((1 - tokens) * windowMs / capacity)\n" +
            "end\n" +
            "redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)\n" +
            "redis.call('PEXPIRE', KEYS[1], windowMs * 2)\n" +
            "return {allowed, math.floor(tokens), waitMs}", List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final String keyPrefix;
    private final Clock clock;

    public RedisRateLimiterRegistry(StringRedisTemplate redis, RateLimitProperties props, String keyPrefix) {
        this(redis, props, keyPrefix, Clock.systemUTC());
    }

    RedisRateLimiterRegistry(StringRedisTemplate redis, RateLimitProperties props, String keyPrefix, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "rate:limit:" : keyPrefix;
        this.clock = clock;
    }

    @Override
    public Decision tryConsume(String tenantId, String family) {
        int qpm = props.resolveQpm(tenantId, family);
        String key = RateLimitKeys.bucketKey(keyPrefix, tenantId, family, qpm);
        try {
            @SuppressWarnings("unchecked")
            List<Long> res = redis.execute(BUCKET, List.of(key),
                    Long.toString(clock.millis()), Integer.toString(qpm), Long.toString(WINDOW_MS));
            if (res == null || res.size() < 3) {
                return failOpen(qpm); // 结果异常按放行处理
            }
            boolean allowed = res.get(0) != null && res.get(0) == 1L;
            long remaining = res.get(1) == null ? 0L : res.get(1);
            long waitMs = res.get(2) == null ? 0L : res.get(2);
            return new Decision(allowed, remaining,
                    allowed ? 0L : RateLimitKeys.retryAfterSeconds(waitMs), qpm);
        } catch (Exception e) {
            // Redis 抖动不该把流量全拒 —— fail-open 放行（限流是整形不是安全边界）
            log.warn("redis rate-limit failed tenant={} family={}: {}", tenantId, family, e.toString());
            return failOpen(qpm);
        }
    }

    private Decision failOpen(int qpm) {
        return new Decision(true, qpm, 0L, qpm);
    }
}
