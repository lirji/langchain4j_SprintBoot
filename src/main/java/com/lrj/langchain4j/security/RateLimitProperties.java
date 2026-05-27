package com.lrj.langchain4j.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code app.rate-limit.*}：per-(tenant, endpoint family) QPM 限制。
 *
 * <p>限流维度选 (tenantId, family) 而不是 (userId, family) 是因为账单维度通常按租户；
 * userId 限流要做也很简单，再 chain 一个 filter。
 *
 * <pre>
 * app.rate-limit:
 *   enabled: true
 *   defaults:                # 每分钟最大请求数
 *     chat: 60
 *     stream: 20             # 流式占连接，限更紧
 *     ingest: 5              # 写操作，限更紧
 *     eval: 5
 *     default: 120           # 没匹配上的 endpoint
 *   anonymous-multiplier: 0.2   # 未鉴权租户（ANONYMOUS）默认配额 × 此倍率
 *   overrides:               # per-tenant 完全覆盖某个 family 的限额
 *     tenantA:
 *       chat: 600            # 给大客户单独提配额
 * </pre>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** family -> QPM。从这里读 baseline。 */
    private Map<String, Integer> defaults = defaultsBootstrap();

    /** anonymous 兜底租户的 multiplier，避免没带 key 也能跑满 default 配额。 */
    private double anonymousMultiplier = 0.2;

    /** tenantId -> { family -> QPM }，覆盖 defaults 对应 family。 */
    private Map<String, Map<String, Integer>> overrides = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Integer> getDefaults() { return defaults; }
    public void setDefaults(Map<String, Integer> defaults) { this.defaults = defaults; }
    public double getAnonymousMultiplier() { return anonymousMultiplier; }
    public void setAnonymousMultiplier(double anonymousMultiplier) { this.anonymousMultiplier = anonymousMultiplier; }
    public Map<String, Map<String, Integer>> getOverrides() { return overrides; }
    public void setOverrides(Map<String, Map<String, Integer>> overrides) { this.overrides = overrides; }

    private static Map<String, Integer> defaultsBootstrap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("chat", 60);
        m.put("stream", 20);
        m.put("ingest", 5);
        m.put("eval", 5);
        m.put("default", 120);
        return m;
    }

    /** 解析当前 (tenant, family) 的有效 QPM；未找到 family 时退到 default。anonymous 套 multiplier。 */
    public int resolveQpm(String tenantId, String family) {
        Map<String, Integer> over = overrides.get(tenantId);
        Integer specific = over == null ? null : over.get(family);
        int base = specific != null ? specific : defaults.getOrDefault(family, defaults.getOrDefault("default", 60));
        if (TenantContext.ANONYMOUS.tenantId().equals(tenantId)) {
            base = Math.max(1, (int) Math.floor(base * anonymousMultiplier));
        }
        return Math.max(1, base);
    }
}
