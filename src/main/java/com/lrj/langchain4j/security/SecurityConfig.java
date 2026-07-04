package com.lrj.langchain4j.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, RateLimitProperties.class, TokenBudgetProperties.class})
public class SecurityConfig {

    /**
     * token 预算计数器后端：{@code in-memory}（默认，单 JVM）/ {@code redis}（多副本共享，
     * 见 {@link RedisTokenBudgetTracker}）。两 Bean 同类型互斥（{@code @ConditionalOnProperty}），
     * 消费方按接口 {@link TokenBudgetTracker} 注入、换后端零改动。
     */
    @Bean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "in-memory", matchIfMissing = true)
    public TokenBudgetTracker inMemoryTokenBudgetTracker(TokenBudgetProperties props) {
        return new InMemoryTokenBudgetTracker(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "redis")
    public TokenBudgetTracker redisTokenBudgetTracker(StringRedisTemplate redis, TokenBudgetProperties props) {
        return new RedisTokenBudgetTracker(redis, props, props.getRedis().getKeyPrefix());
    }

    /**
     * 限流器后端：{@code in-memory}（默认，单 JVM Bucket4j 桶）/ {@code redis}（多副本共享同一个桶，
     * 见 {@link RedisRateLimiterRegistry}）。两 Bean 同类型互斥（{@code @ConditionalOnProperty}），
     * {@link RateLimitFilter} 按接口 {@link RateLimiterRegistry} 注入、换后端零改动。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "in-memory", matchIfMissing = true)
    public RateLimiterRegistry inMemoryRateLimiterRegistry(RateLimitProperties props) {
        return new InMemoryRateLimiterRegistry(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis")
    public RateLimiterRegistry redisRateLimiterRegistry(StringRedisTemplate redis, RateLimitProperties props) {
        return new RedisRateLimiterRegistry(redis, props, props.getRedis().getKeyPrefix());
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(SecurityProperties props, com.lrj.langchain4j.audit.AuditLogger audit) {
        return new ApiKeyAuthFilter(props, audit);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties props, RateLimiterRegistry registry,
                                           com.lrj.langchain4j.audit.AuditLogger audit) {
        return new RateLimitFilter(props, registry, audit);
    }

    @Bean
    public TokenBudgetGuardFilter tokenBudgetGuardFilter(TokenBudgetProperties props, TokenBudgetTracker tracker,
                                                         com.lrj.langchain4j.audit.AuditLogger audit) {
        return new TokenBudgetGuardFilter(props, tracker, audit);
    }

    /**
     * 默认链：
     * <ul>
     *   <li>{@code /actuator/health/**} / {@code /actuator/prometheus} / {@code /health} 放行
     *       （监控 / K8s probe / Prometheus scrape 不能带 user key）</li>
     *   <li>其余全部要求已认证。{@code ApiKeyAuthFilter} 把合法 key 转成已认证 token。</li>
     *   <li>无 session（STATELESS），CSRF 关（纯 API）。</li>
     * </ul>
     *
     * 设 {@code app.security.enabled=false} 时走 {@link #disabledSecurityFilterChain}：全放行。
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   TokenBudgetGuardFilter tokenBudgetGuardFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/actuator/tokenbudget",
                                "/actuator/cost",
                                "/health",
                                // 飞书事件订阅 / 卡片回调：不带 X-Api-Key，用飞书自带验签解密（FeishuController）
                                "/channel/feishu/**",
                                // A2A 服务发现：Agent Card 公开可读，免鉴权（POST /a2a 仍需 X-Api-Key）
                                "/.well-known/agent-card.json"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 顺序：auth → rate-limit (QPS) → token-budget (成本)。
                // rate-limit 拒绝更便宜（无 I/O），让它先 short-circuit；token-budget 才查 tracker
                .addFilterAfter(rateLimitFilter, ApiKeyAuthFilter.class)
                .addFilterAfter(tokenBudgetGuardFilter, RateLimitFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .build();
    }

    /** 本地 demo 用：彻底跳过 auth；TenantContext 仍是 ANONYMOUS 兜底。限流也跟着关。 */
    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "false")
    public SecurityFilterChain disabledSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
