package com.lrj.langchain4j.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code app.token-budget.*}：per-tenant 日 token 预算。覆盖 chat / extract / multi-agent /
 * reflexive / eval 等触发 LLM 调用的 endpoint；{@code /rag/ingest} 走 embedding model 暂不计入。
 *
 * <p>跟 {@link RateLimitProperties}（限并发/QPS）是两个维度：限流挡突发，token budget 控成本。
 * 一个租户可以 60 QPM 不超限，但如果每次都让 multi-agent 烧 5k token，1 天下来仍会爆账单。
 *
 * <p>MVP 不区分 model/provider 定价 —— 所有 token（input + output 一视同仁）累加。
 * 后续要做 cost-based 限制（按 USD），可以在 {@code TokenBudgetChatModelListener} 里乘上
 * 一个 model→price 表，把 budget 单位从 "tokens/day" 改成 "USD/day"。
 *
 * <pre>
 * app.token-budget:
 *   enabled: true
 *   timezone: Asia/Shanghai      # 日历日重置以哪个时区为准
 *   daily-tokens:
 *     default: 100000            # 默认每天 10 万 token / tenant
 *     overrides:
 *       tenantA: 500000          # 大客户单独提配额
 *   anonymous-multiplier: 0.05   # anonymous（未鉴权）只享受 default 的 5%
 * </pre>
 */
@ConfigurationProperties(prefix = "app.token-budget")
public class TokenBudgetProperties {

    private boolean enabled = true;

    /**
     * 计数后端：{@code in-memory}（默认，进程内、限单 JVM）/ {@code redis}（多副本共享计数，
     * 多 pod 部署下配额才真正生效）。切 redis 需 {@code spring.data.redis.*} 可用。
     */
    private String store = "in-memory";

    /** 日历日重置依据的时区。未设 → ZoneId.systemDefault()。建议显式配避免随服务器时区漂移。 */
    private String timezone;

    private DailyTokens dailyTokens = new DailyTokens();

    /** Redis 后端配置（仅 {@code store=redis} 用到）。 */
    private Redis redis = new Redis();

    /** anonymous 兜底租户的预算倍率，避免关闭 auth 时无限烧 token。 */
    private double anonymousMultiplier = 0.05;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public DailyTokens getDailyTokens() { return dailyTokens; }
    public void setDailyTokens(DailyTokens dailyTokens) { this.dailyTokens = dailyTokens; }
    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }
    public double getAnonymousMultiplier() { return anonymousMultiplier; }
    public void setAnonymousMultiplier(double anonymousMultiplier) { this.anonymousMultiplier = anonymousMultiplier; }

    public static class Redis {
        /** key 前缀。实际 key 为 {@code <prefix><date>:<tenantId>}，date 内嵌使跨日 key 自然区隔 + 自动过期。 */
        private String keyPrefix = "token:budget:";
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    }

    public long resolveDailyBudget(String tenantId) {
        Long override = dailyTokens.getOverrides().get(tenantId);
        long base = override != null ? override : dailyTokens.getDefault();
        if (TenantContext.ANONYMOUS.tenantId().equals(tenantId)) {
            base = Math.max(1L, (long) Math.floor(base * anonymousMultiplier));
        }
        return Math.max(1L, base);
    }

    public static class DailyTokens {
        // 字段名是 default —— @ConfigurationProperties 接受 `default:` 键。getter/setter 用别名避开关键字
        private long defaultBudget = 100_000L;
        private Map<String, Long> overrides = new HashMap<>();

        // Spring binder 看的是 setter 名去掉 set 后驼峰 → 这里映射 yml 里的 `default:`
        public long getDefault() { return defaultBudget; }
        public void setDefault(long defaultBudget) { this.defaultBudget = defaultBudget; }
        public Map<String, Long> getOverrides() { return overrides; }
        public void setOverrides(Map<String, Long> overrides) { this.overrides = overrides; }
    }
}
