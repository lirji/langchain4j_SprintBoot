package com.lrj.langchain4j.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed per-tenant token 预算计数器 —— 让配额在<strong>多副本 / 多 pod</strong> 下真正生效
 * （进程内的 {@link InMemoryTokenBudgetTracker} 每个副本各算各的，等于把限额放大到副本数倍）。
 *
 * <p>落地当初 {@code InMemory} 版注释里承诺的演进路径："把 map 换成 Redis（INCRBY tenant:tokens:DATE +
 * 次日 0 点过期），业务接口保持不变"。key 里内嵌<strong>日期</strong>（配置时区），所以：
 * <ul>
 *   <li><strong>跨日重置零成本</strong>：新的一天自然换新 key、旧 key 到点自动过期，无需定时清理任务；</li>
 *   <li><strong>累加原子</strong>：{@code consume} 走 Lua（{@code INCRBY} + {@code PEXPIREAT} 一次往返、
 *       服务端原子执行），并发多 pod 不丢更新、不覆盖。</li>
 * </ul>
 *
 * <p>与预检的一致性：沿用 in-memory 版语义 —— 先 {@code currentUsed} 预检、请求跑完再 {@code consume}
 * 回填（最终一致，单请求可能轻微越额）。要严格"预扣"可把预检也并进 Lua，属未来项。
 *
 * <p>key 布局 {@code <prefix><date>:<tenantId>}（date 在前、tenantId 在末段），使 {@link #snapshotAll}
 * 能用 {@code SCAN <prefix><today>:*} 枚举、按定长前缀切出 tenantId（tenantId 含 {@code :} 也不影响）。
 */
public class RedisTokenBudgetTracker implements TokenBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBudgetTracker.class);

    /** INCRBY + 绝对过期（次日午夜 epoch millis）：一次往返、服务端原子。返回累加后的值。 */
    private static final RedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>(
            "local v = redis.call('INCRBY', KEYS[1], ARGV[1])\n" +
            "redis.call('PEXPIREAT', KEYS[1], ARGV[2])\n" +
            "return v", Long.class);

    private final StringRedisTemplate redis;
    private final TokenBudgetProperties props;
    private final String keyPrefix;
    private final Clock clock;

    public RedisTokenBudgetTracker(StringRedisTemplate redis, TokenBudgetProperties props, String keyPrefix) {
        this(redis, props, keyPrefix, Clock.system(InMemoryTokenBudgetTracker.resolveZone(props)));
    }

    RedisTokenBudgetTracker(StringRedisTemplate redis, TokenBudgetProperties props, String keyPrefix, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "token:budget:" : keyPrefix;
        this.clock = clock;
    }

    @Override
    public void consume(String tenantId, long tokens) {
        if (tokens <= 0 || tenantId == null) return;
        String key = RedisDailyCounters.dayKey(keyPrefix, LocalDate.now(clock), tenantId);
        try {
            redis.execute(INCR_EXPIRE, List.of(key),
                    Long.toString(tokens), Long.toString(RedisDailyCounters.nextMidnightMillis(clock)));
        } catch (Exception e) {
            // Redis 抖动不该拖垮主链路（成本记账 best-effort）；漏记比拒服务代价小
            log.warn("redis token-budget consume failed tenant={} +{}: {}", tenantId, tokens, e.toString());
        }
    }

    @Override
    public long currentUsed(String tenantId) {
        String key = RedisDailyCounters.dayKey(keyPrefix, LocalDate.now(clock), tenantId);
        try {
            return parseLong(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("redis token-budget read failed tenant={}: {}", tenantId, e.toString());
            return 0L; // 读失败按 0 处理：宁可放行也不误拒（拦截由 rate-limit 兜底）
        }
    }

    @Override
    public boolean wouldExceed(String tenantId) {
        return currentUsed(tenantId) >= props.resolveDailyBudget(tenantId);
    }

    @Override
    public long secondsUntilReset() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime midnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
        return Math.max(1L, java.time.Duration.between(now, midnight).getSeconds());
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
                long used = parseLong(redis.opsForValue().get(key));
                out.put(tenantId, new Snapshot(used, props.resolveDailyBudget(tenantId), today.toString()));
            }
        } catch (Exception e) {
            log.warn("redis token-budget snapshot scan failed: {}", e.toString());
        }
        return out;
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
