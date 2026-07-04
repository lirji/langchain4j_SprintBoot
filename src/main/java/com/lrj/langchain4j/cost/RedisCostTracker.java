package com.lrj.langchain4j.cost;

import com.lrj.langchain4j.security.RedisDailyCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed per-tenant 日 USD 成本累加器 —— 让多副本的成本汇总到<strong>同一份账</strong>
 * （进程内的 {@link InMemoryCostTracker} 各 pod 各算各的，看板需手动加总）。
 *
 * <p>与 {@link com.lrj.langchain4j.security.RedisTokenBudgetTracker} 同款范式，共用
 * {@link RedisDailyCounters}（key 布局 / 租户解析 / 次日午夜过期）；差别只在累加用 {@code INCRBYFLOAT}
 * （成本是小数）而非 {@code INCRBY}。key {@code <prefix><date>:<tenantId>} 内嵌日期 → 跨日自动过期免清理。
 *
 * <p>Redis 抖动不拖垮主链路：{@code record} 失败仅告警（成本记账 best-effort，漏记比拒服务代价小）。
 */
public class RedisCostTracker implements CostTracker {

    private static final Logger log = LoggerFactory.getLogger(RedisCostTracker.class);

    /** INCRBYFLOAT + 绝对过期（次日午夜 epoch millis）：一次往返、服务端原子。 */
    private static final RedisScript<Void> INCR_EXPIRE = new DefaultRedisScript<>(
            "redis.call('INCRBYFLOAT', KEYS[1], ARGV[1])\n" +
            "redis.call('PEXPIREAT', KEYS[1], ARGV[2])", Void.class);

    private final StringRedisTemplate redis;
    private final String currency;
    private final String keyPrefix;
    private final Clock clock;

    public RedisCostTracker(StringRedisTemplate redis, String timezone, String currency, String keyPrefix) {
        this(redis, currency, keyPrefix, Clock.system(InMemoryCostTracker.resolveZone(timezone)));
    }

    RedisCostTracker(StringRedisTemplate redis, String currency, String keyPrefix, Clock clock) {
        this.redis = redis;
        this.currency = (currency == null || currency.isBlank()) ? "USD" : currency;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "cost:usd:" : keyPrefix;
        this.clock = clock;
    }

    @Override
    public void record(String tenantId, double usd) {
        if (usd <= 0 || tenantId == null) return;
        String key = RedisDailyCounters.dayKey(keyPrefix, LocalDate.now(clock), tenantId);
        try {
            redis.execute(INCR_EXPIRE, List.of(key),
                    Double.toString(usd), Long.toString(RedisDailyCounters.nextMidnightMillis(clock)));
        } catch (Exception e) {
            log.warn("redis cost record failed tenant={} +${}: {}", tenantId, usd, e.toString());
        }
    }

    @Override
    public double currentUsd(String tenantId) {
        String key = RedisDailyCounters.dayKey(keyPrefix, LocalDate.now(clock), tenantId);
        try {
            return parseDouble(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("redis cost read failed tenant={}: {}", tenantId, e.toString());
            return 0.0;
        }
    }

    @Override
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);
        String prefix = RedisDailyCounters.scanPrefix(keyPrefix, today);
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(prefix + "*").count(256).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String tenantId = RedisDailyCounters.tenantFromKey(prefix, key);
                if (tenantId == null) continue;
                double usd = parseDouble(redis.opsForValue().get(key));
                out.put(tenantId, new Snapshot(InMemoryCostTracker.round(usd), currency, today.toString()));
            }
        } catch (Exception e) {
            log.warn("redis cost snapshot scan failed: {}", e.toString());
        }
        return out;
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
