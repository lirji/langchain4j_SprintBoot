package com.lrj.langchain4j.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测「限流桶落 Redis」范式的共享纯函数 helper {@link RateLimitKeys} —— 桶 key 布局 + retry-after 换算。
 * 不连 Redis；Lua token bucket 往返属集成，靠真 Redis 起服务验证（见 docs/distributed-state.md）。
 */
class RateLimitKeysTest {

    @Test
    void bucketKey_embedsTenantFamilyQpm() {
        assertThat(RateLimitKeys.bucketKey("rate:limit:", "acme", "chat", 60))
                .isEqualTo("rate:limit:acme|chat|60");
    }

    @Test
    void bucketKey_qpmInKey_differsWhenLimitChanges() {
        // qpm 编进 key → 限额一变就是新 key（= 新满桶），无需显式失效旧桶
        String a = RateLimitKeys.bucketKey("rate:limit:", "acme", "chat", 60);
        String b = RateLimitKeys.bucketKey("rate:limit:", "acme", "chat", 600);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void bucketKey_emptyPrefix_forInMemoryMapKey() {
        // 进程内 registry 复用同一 key 布局但前缀为空
        assertThat(RateLimitKeys.bucketKey("", "acme", "stream", 20)).isEqualTo("acme|stream|20");
    }

    @Test
    void retryAfterSeconds_roundsUpAndFloorsAtOne() {
        assertThat(RateLimitKeys.retryAfterSeconds(0)).isEqualTo(1L);      // 被拒至少回 1s
        assertThat(RateLimitKeys.retryAfterSeconds(-5)).isEqualTo(1L);
        assertThat(RateLimitKeys.retryAfterSeconds(1)).isEqualTo(1L);
        assertThat(RateLimitKeys.retryAfterSeconds(1000)).isEqualTo(1L);
        assertThat(RateLimitKeys.retryAfterSeconds(1001)).isEqualTo(2L);   // 向上取整
        assertThat(RateLimitKeys.retryAfterSeconds(2500)).isEqualTo(3L);
    }
}
