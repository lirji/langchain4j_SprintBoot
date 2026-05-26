package com.lrj.langchain4j.observability;

import com.lrj.langchain4j.config.LlmConfig.LlmProperties;
import com.lrj.langchain4j.config.LlmConfig.OpenAiCompatProps;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Actuator HealthIndicator：对当前激活的 chat provider 的 base-url 做 TCP 连通性探测。
 *
 * <p>不发 LLM 请求 —— 因此：
 * <ul>
 *   <li>不烧 token、不计费</li>
 *   <li>不需要 api-key 有效（许多托管 API 的 /v1/models 要 auth，TCP 不需要）</li>
 *   <li>能在 1s 内回结果，不阻塞 K8s readiness probe</li>
 * </ul>
 *
 * <p>反映的是网络层健康（vLLM pod 是否就绪 / DNS 解析 / 路由），不反映模型实际可推理 ——
 * 后者要靠真实流量监控（{@code gen_ai.client.errors} 指标）。
 *
 * <p>暴露在 {@code GET /actuator/health}，下面会有 {@code "llm": {"status": "UP", ...}} 节点。
 */
@Component("llm")
public class LlmHealthIndicator implements HealthIndicator {

    private static final int CONNECT_TIMEOUT_MS = 1000;

    private final LlmProperties props;

    public LlmHealthIndicator(LlmProperties props) {
        this.props = props;
    }

    @Override
    public Health health() {
        String provider = props.getProvider();
        String target = resolveBaseUrl(provider);
        if (target == null) {
            return Health.unknown()
                    .withDetail("provider", provider)
                    .withDetail("reason", "no base-url configured for this provider")
                    .build();
        }
        return probeTcp(target, "provider", provider, "target", target);
    }

    private String resolveBaseUrl(String provider) {
        return switch (provider == null ? "ollama" : provider.toLowerCase()) {
            case "ollama" -> props.getOllama().getBaseUrl();
            case "openai" -> orDefault(props.getOpenai(), "https://api.openai.com");
            case "deepseek" -> orDefault(props.getDeepseek(), null);
            case "vllm" -> orDefault(props.getVllm(), null);
            case "anthropic" -> "https://api.anthropic.com";
            case "gemini" -> "https://generativelanguage.googleapis.com";
            default -> null;
        };
    }

    private static String orDefault(OpenAiCompatProps p, String fallback) {
        if (p != null && p.getBaseUrl() != null && !p.getBaseUrl().isBlank()) {
            return p.getBaseUrl();
        }
        return fallback;
    }

    /** 共享给 EmbeddingHealthIndicator 的 TCP 探测实现 —— 1s 超时 + 端口推断。 */
    static Health probeTcp(String url, String... extraDetails) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                return Health.down()
                        .withDetail("reason", "could not parse host from url")
                        .withDetail("url", url)
                        .build();
            }
            int port = uri.getPort();
            if (port < 0) {
                // 没显式端口 → 按协议推断
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            Health.Builder b = Health.up()
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("tcpConnectMs", ms);
            for (int i = 0; i + 1 < extraDetails.length; i += 2) {
                b.withDetail(extraDetails[i], extraDetails[i + 1]);
            }
            return b.build();
        } catch (URISyntaxException e) {
            return Health.down()
                    .withDetail("reason", "invalid url syntax")
                    .withDetail("url", url)
                    .withException(e)
                    .build();
        } catch (Exception e) {
            Health.Builder b = Health.down()
                    .withDetail("url", url)
                    .withException(e);
            for (int i = 0; i + 1 < extraDetails.length; i += 2) {
                b.withDetail(extraDetails[i], extraDetails[i + 1]);
            }
            return b.build();
        }
    }
}
