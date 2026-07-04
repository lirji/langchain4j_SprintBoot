package com.lrj.langchain4j.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code app.cost.*}：把 token 用量翻成 USD 的定价表 —— per-tenant 成本归因的唯一事实来源。
 *
 * <p>补 {@link com.lrj.langchain4j.security.TokenBudgetProperties} 埋的坑：token budget 按
 * "tokens/day" 一视同仁计量，无法区分 gpt-4o（贵）和 ollama（免费）。本表给每个 model 配
 * input / output / cache-read / cache-write 四档单价（<strong>USD / 1M tokens</strong>，云厂商标准口径），
 * 由 {@link CostCalculator} 乘出每次调用的 USD，{@link CostTracker} 按租户日累加，
 * {@link CostChatModelListener} 同时打进 Micrometer（{@code gen_ai.client.cost.usd}）。
 *
 * <p>{@code enabled=false}（默认）时不装配 calculator / tracker / listener，零开销、零回归 ——
 * 本地 ollama 场景本就免费，无需成本核算。
 *
 * <p>model 匹配：先精确命中，否则取<strong>最长前缀</strong>命中（配 {@code gpt-4o-mini} 能匹配
 * {@code gpt-4o-mini-2024-07-18}），都不中回退 {@code default}。这样定价表不用穷举模型全版本号。
 *
 * <pre>
 * app.cost:
 *   enabled: true
 *   currency: USD
 *   default:                         # 未在表中命中的 model 兜底（本地 ollama 建议 0）
 *     input: 0.0
 *     output: 0.0
 *   pricing:                         # 单位：USD / 1,000,000 tokens
 *     gpt-4o-mini:   { input: 0.15,  output: 0.60 }
 *     gpt-4o:        { input: 2.50,  output: 10.00 }
 *     claude-haiku-4-5: { input: 1.00, output: 5.00, cache-read: 0.10, cache-write: 1.25 }
 *     deepseek-chat: { input: 0.27,  output: 1.10 }
 * </pre>
 */
@ConfigurationProperties(prefix = "app.cost")
public class CostProperties {

    private boolean enabled = false;

    /**
     * 累加后端：{@code in-memory}（默认，进程内）/ {@code redis}（多副本成本汇总同一份账，
     * 见 {@link RedisCostTracker}）。切 redis 需 {@code spring.data.redis.*} 可用。
     */
    private String store = "in-memory";

    /** 展示用货币代码（不参与换算，只随快照/指标出现）。 */
    private String currency = "USD";

    /** Redis 后端配置（仅 {@code store=redis} 用到）。 */
    private Redis redis = new Redis();

    /** 表中未命中的 model 兜底单价。默认全 0 —— 本地免费模型不产生成本。 */
    private Rate defaultRate = new Rate();

    /** model → 单价。key 支持精确名或前缀（见类注释匹配规则）。 */
    private Map<String, Rate> pricing = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }
    public Rate getDefault() { return defaultRate; }
    public void setDefault(Rate defaultRate) { this.defaultRate = defaultRate; }
    public Map<String, Rate> getPricing() { return pricing; }
    public void setPricing(Map<String, Rate> pricing) { this.pricing = pricing; }

    /**
     * 解析某 model 的单价：精确命中 → 最长前缀命中 → {@link #defaultRate}。
     * model 为 null/blank 时也回退默认（不抛异常，成本核算不该拖垮主链路）。
     */
    public Rate resolveRate(String model) {
        if (model != null && !model.isBlank()) {
            Rate exact = pricing.get(model);
            if (exact != null) return exact;
            Rate best = null;
            int bestLen = -1;
            for (Map.Entry<String, Rate> e : pricing.entrySet()) {
                String key = e.getKey();
                if (key != null && model.startsWith(key) && key.length() > bestLen) {
                    best = e.getValue();
                    bestLen = key.length();
                }
            }
            if (best != null) return best;
        }
        return defaultRate;
    }

    public static class Redis {
        /** key 前缀。实际 key 为 {@code <prefix><date>:<tenantId>}，date 内嵌使跨日 key 自然区隔 + 自动过期。 */
        private String keyPrefix = "cost:usd:";
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    }

    /** 四档单价，单位 USD / 1M tokens。cache-* 仅 Anthropic prompt caching 用得上，默认回退 input 价。 */
    public static class Rate {
        private double input = 0.0;
        private double output = 0.0;
        /** 命中缓存的输入 token 单价（Anthropic 约 input 的 0.1）。<0 表示"未配"→ 回退 input 价。 */
        private double cacheRead = -1.0;
        /** 建缓存的输入 token 单价（Anthropic 约 input 的 1.25）。<0 表示"未配"→ 回退 input 价。 */
        private double cacheWrite = -1.0;

        public double getInput() { return input; }
        public void setInput(double input) { this.input = input; }
        public double getOutput() { return output; }
        public void setOutput(double output) { this.output = output; }
        public double getCacheRead() { return cacheRead; }
        public void setCacheRead(double cacheRead) { this.cacheRead = cacheRead; }
        public double getCacheWrite() { return cacheWrite; }
        public void setCacheWrite(double cacheWrite) { this.cacheWrite = cacheWrite; }

        /** cache-read 有效单价：未配（<0）时回退 input 价。 */
        public double effectiveCacheRead() { return cacheRead >= 0 ? cacheRead : input; }
        /** cache-write 有效单价：未配（<0）时回退 input 价。 */
        public double effectiveCacheWrite() { return cacheWrite >= 0 ? cacheWrite : input; }
    }
}
