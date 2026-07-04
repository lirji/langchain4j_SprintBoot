package com.lrj.langchain4j.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内 token-bucket 限流器 {@link InMemoryRateLimiterRegistry} 的确定性行为：
 * 桶容量内放行、超出 429、租户/family 隔离、anonymous 倍率、qpm 变更即新满桶。
 * 快速连续消费下 Bucket4j 的贪婪补桶来不及补出整 token，故断言稳定不 flaky。
 */
class InMemoryRateLimiterRegistryTest {

    private static RateLimitProperties propsWith(Map<String, Integer> defaults) {
        RateLimitProperties p = new RateLimitProperties();
        p.setDefaults(new HashMap<>(defaults));
        return p;
    }

    @Test
    void allowsUpToCapacityThenBlocks() {
        RateLimiterRegistry reg = new InMemoryRateLimiterRegistry(propsWith(Map.of("chat", 3)));

        RateLimiterRegistry.Decision d1 = reg.tryConsume("acme", "chat");
        RateLimiterRegistry.Decision d2 = reg.tryConsume("acme", "chat");
        RateLimiterRegistry.Decision d3 = reg.tryConsume("acme", "chat");
        RateLimiterRegistry.Decision d4 = reg.tryConsume("acme", "chat");

        assertThat(d1.allowed()).isTrue();
        assertThat(d1.limit()).isEqualTo(3);
        assertThat(d1.remainingTokens()).isEqualTo(2);
        assertThat(d3.allowed()).isTrue();
        assertThat(d3.remainingTokens()).isEqualTo(0);

        assertThat(d4.allowed()).isFalse();
        assertThat(d4.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(d4.remainingTokens()).isEqualTo(0);
    }

    @Test
    void tenantsAreIsolated() {
        RateLimiterRegistry reg = new InMemoryRateLimiterRegistry(propsWith(Map.of("chat", 1)));

        assertThat(reg.tryConsume("acme", "chat").allowed()).isTrue();
        assertThat(reg.tryConsume("acme", "chat").allowed()).isFalse(); // acme 用尽
        assertThat(reg.tryConsume("globex", "chat").allowed()).isTrue(); // 另一租户不受影响
    }

    @Test
    void familiesAreIsolated() {
        RateLimiterRegistry reg = new InMemoryRateLimiterRegistry(propsWith(Map.of("chat", 1, "stream", 1)));

        assertThat(reg.tryConsume("acme", "chat").allowed()).isTrue();
        assertThat(reg.tryConsume("acme", "chat").allowed()).isFalse(); // chat 用尽
        assertThat(reg.tryConsume("acme", "stream").allowed()).isTrue(); // stream 独立
    }

    @Test
    void anonymousMultiplierShrinksLimit() {
        RateLimitProperties p = propsWith(Map.of("chat", 10));
        p.setAnonymousMultiplier(0.2); // floor(10 * 0.2) = 2
        RateLimiterRegistry reg = new InMemoryRateLimiterRegistry(p);

        String anon = TenantContext.ANONYMOUS.tenantId();
        assertThat(reg.tryConsume(anon, "chat").limit()).isEqualTo(2);
        assertThat(reg.tryConsume(anon, "chat").allowed()).isTrue();
        assertThat(reg.tryConsume(anon, "chat").allowed()).isFalse(); // 2 个就到顶
    }

    @Test
    void qpmChangeGivesFreshBucket() {
        RateLimitProperties p = propsWith(Map.of("chat", 60));
        Map<String, Map<String, Integer>> overrides = new HashMap<>();
        Map<String, Integer> acme = new HashMap<>();
        acme.put("chat", 1);
        overrides.put("acme", acme);
        p.setOverrides(overrides);
        RateLimiterRegistry reg = new InMemoryRateLimiterRegistry(p);

        assertThat(reg.tryConsume("acme", "chat").allowed()).isTrue();
        assertThat(reg.tryConsume("acme", "chat").allowed()).isFalse(); // qpm=1 用尽

        acme.put("chat", 5); // 热更提配额 → qpm 编进 key，是全新满桶
        RateLimiterRegistry.Decision after = reg.tryConsume("acme", "chat");
        assertThat(after.allowed()).isTrue();
        assertThat(after.limit()).isEqualTo(5);
        assertThat(after.remainingTokens()).isEqualTo(4);
    }
}
