package com.lrj.langchain4j.cost;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 per-tenant USD 成本核算链，仅 {@code app.cost.enabled=true} 时生效（默认关，本地免费模型无需）。
 *
 * <p>{@link CostChatModelListener} 声明成 {@link ChatModelListener} Bean → {@code LlmConfig} 的
 * {@code List<ChatModelListener>} 构造注入自动把它灌进每个 chat builder，无需改 LlmConfig
 * （跟 logging/metrics/token-budget listener 同一收集机制）。
 */
@Configuration
@EnableConfigurationProperties(CostProperties.class)
@ConditionalOnProperty(name = "app.cost.enabled", havingValue = "true")
public class CostConfig {

    @Bean
    public CostCalculator costCalculator(CostProperties props) {
        return new CostCalculator(props);
    }

    // 复用 token-budget 的时区口径（同一日历日重置基准），避免成本/配额两个面板跨日时刻不一致
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.cost.store", havingValue = "in-memory", matchIfMissing = true)
    public CostTracker inMemoryCostTracker(CostProperties props,
                                           @org.springframework.beans.factory.annotation.Value("${app.token-budget.timezone:}") String timezone) {
        return new InMemoryCostTracker(timezone, props.getCurrency());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.cost.store", havingValue = "redis")
    public CostTracker redisCostTracker(CostProperties props,
                                        org.springframework.data.redis.core.StringRedisTemplate redis,
                                        @org.springframework.beans.factory.annotation.Value("${app.token-budget.timezone:}") String timezone) {
        return new RedisCostTracker(redis, timezone, props.getCurrency(), props.getRedis().getKeyPrefix());
    }

    @Bean
    public ChatModelListener costChatModelListener(CostCalculator calculator,
                                                   CostTracker tracker,
                                                   MeterRegistry registry) {
        return new CostChatModelListener(calculator, tracker, registry);
    }

    @Bean
    public CostEndpoint costEndpoint(CostTracker tracker) {
        return new CostEndpoint(tracker);
    }
}
