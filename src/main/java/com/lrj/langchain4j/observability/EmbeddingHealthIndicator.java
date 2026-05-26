package com.lrj.langchain4j.observability;

import com.lrj.langchain4j.config.EmbeddingModelConfig.EmbeddingProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Embedding 服务的 TCP 连通性 health check —— 对 {@code app.embedding.provider} 选中的
 * embedding 后端做探测。同 {@link LlmHealthIndicator} 的策略，复用其 TCP 探测实现。
 *
 * <p>暴露在 {@code GET /actuator/health}，下面会有 {@code "embedding": {"status": "UP", ...}} 节点。
 */
@Component("embedding")
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final EmbeddingProperties props;

    public EmbeddingHealthIndicator(EmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public Health health() {
        String provider = props.getProvider();
        String target = switch (provider == null ? "ollama" : provider.toLowerCase()) {
            case "ollama" -> props.getOllama().getBaseUrl();
            case "openai-compat" -> props.getOpenaiCompat().getBaseUrl();
            default -> null;
        };
        if (target == null) {
            return Health.unknown()
                    .withDetail("provider", provider)
                    .withDetail("reason", "no base-url configured for this provider")
                    .build();
        }
        return LlmHealthIndicator.probeTcp(target, "provider", provider, "target", target);
    }
}
