package com.lrj.langchain4j.async.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.async.webhook.*}。
 *
 * <pre>
 * app.async.webhook:
 *   enabled: true
 *   hmac-secret: ${WEBHOOK_HMAC_SECRET:dev-secret-change-me}
 *   timeout: PT5S       # 单次 HTTP 请求超时
 *   max-retries: 3      # 失败重试次数（不计首次）
 *   backoff: PT1S       # 首次重试间隔；指数退避 base*1, base*3, base*9 ...
 * </pre>
 *
 * <p>HMAC：用 secret 做 HMAC-SHA256(body)，hex 编码，放 {@code X-Webhook-Signature: sha256=...} header。
 * 客户端拿 secret + 收到的 body 自行计算对比，防止 webhook URL 泄露导致的伪造请求。
 *
 * <p>secret 用环境变量传；yml 里写 dev fallback。生产 K8s Secret / Vault。
 */
@ConfigurationProperties(prefix = "app.async.webhook")
public class WebhookProperties {

    private boolean enabled = true;
    private String hmacSecret = "dev-secret-change-me";
    private Duration timeout = Duration.ofSeconds(5);
    private int maxRetries = 3;
    private Duration backoff = Duration.ofSeconds(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Duration getBackoff() { return backoff; }
    public void setBackoff(Duration backoff) { this.backoff = backoff; }
}
