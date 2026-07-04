package com.lrj.langchain4j.security;

/**
 * per-(tenant, endpoint family) 限流器的抽象。两种后端（照 {@link TokenBudgetTracker} 范式）：
 * <ul>
 *   <li>{@link InMemoryRateLimiterRegistry}（默认，{@code app.rate-limit.store=in-memory}）——
 *       进程内 Bucket4j token bucket，<strong>限单 JVM</strong>：多副本各持一份桶，
 *       同一租户的 QPM 实际被放大到副本数倍（限流形同虚设）。</li>
 *   <li>{@link RedisRateLimiterRegistry}（{@code app.rate-limit.store=redis}）——
 *       token bucket 状态落 Redis（Lua 原子「补桶 + 消费」一次往返），
 *       <strong>多 pod 共享同一个桶</strong>，是限流在水平扩容下真正生效的前提。</li>
 * </ul>
 *
 * <p>消费方（{@link RateLimitFilter}）只依赖本接口 + {@link Decision}，换后端零改动 ——
 * 正是当初 {@code RateLimiterRegistry} / pom 注释里承诺的"切 bucket4j-redis ProxyManager 即可，
 * filter / properties / 调用方都不动"。这里没走 bucket4j-redis（要引 lettuce ProxyManager 新依赖），
 * 而是复用已在的 {@code spring-boot-starter-data-redis} 手写 Lua token bucket，跟 token/cost 两处一致地<strong>零新依赖</strong>。
 */
public interface RateLimiterRegistry {

    /**
     * 尝试为 {@code (tenantId, family)} 消费 1 个 token。
     * 有效 QPM 由 {@link RateLimitProperties#resolveQpm} 解析（含 anonymous 倍率 + per-tenant override）。
     */
    Decision tryConsume(String tenantId, String family);

    /**
     * 单次限流判定结果。
     *
     * @param allowed           是否放行
     * @param remainingTokens   消费后桶内剩余（用于 {@code X-RateLimit-Remaining}）
     * @param retryAfterSeconds 被拒时到下一个 token 可用的秒数（{@code >=1}）；放行时为 0
     * @param limit             本次生效的 QPM（用于 {@code X-RateLimit-Limit}）
     */
    record Decision(boolean allowed, long remainingTokens, long retryAfterSeconds, int limit) {}
}
