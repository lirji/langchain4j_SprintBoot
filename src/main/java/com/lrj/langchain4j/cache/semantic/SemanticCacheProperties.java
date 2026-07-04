package com.lrj.langchain4j.cache.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.cache.semantic.*}：语义响应缓存。<strong>默认关</strong>（{@code enabled=false}）——
 * 关闭时整条缓存链不装配，{@code /chat} 行为与历史完全一致。
 *
 * <p>动机：对话类流量里存在大量语义等价但字面不同的重复提问（"怎么退款" / "退款流程是啥" /
 * "我要退货怎么弄"）。逐字缓存（精确 key）命中率极低；语义缓存用向量相似度把"意思一样"的问题
 * 归并——命中即直接返回历史答案，<strong>0 LLM token</strong>。
 *
 * <p>与其它维度的关系：
 * <ul>
 *   <li>跟 <b>ChatMemory</b>（会话内多轮上下文）正交——缓存是跨会话/跨用户的"同租户问答复用"；</li>
 *   <li>跟 <b>token-budget</b>（限成本）互补——缓存直接把重复问答的 token 成本降到 0；</li>
 *   <li>跟 <b>RAG grounding</b> 正交——命中即短路，不再触发检索/生成。</li>
 * </ul>
 *
 * <p><strong>命中的代价</strong>：每次 miss 仍要 embed 一次 query（一次 embedding 调用，
 * 远比一次 chat completion 便宜）。命中率越高越划算。
 *
 * <pre>
 * app.cache.semantic:
 *   enabled: false          # 总开关，默认关
 *   threshold: 0.95         # cosine 相似度阈值，>= 才算命中；越高越保守（越不容易误命中）
 *   max-entries: 1000       # 每租户缓存条数上限，超出按 LRU 淘汰
 *   ttl: 2h                 # 单条存活时长；0 或负 = 永不过期
 * </pre>
 */
@ConfigurationProperties(prefix = "app.cache.semantic")
public class SemanticCacheProperties {

    /** 总开关。关闭（默认）时整条语义缓存链不装配。 */
    private boolean enabled = false;

    /**
     * cosine 相似度命中阈值，取值 [-1,1]。历史问题与当前 query 的相似度 {@code >= threshold}
     * 才判命中。默认 0.95 偏保守——语义缓存最怕"意思其实不同却误命中返回错答案"，宁可 miss。
     */
    private double threshold = 0.95;

    /** 每租户缓存条数上限（非全局）。超出按 LRU（最近最少使用）淘汰最旧/最少命中的条目。 */
    private int maxEntries = 1000;

    /**
     * 单条缓存的存活时长。命中时若条目已过期则视为 miss 并顺手清除。
     * {@code 0} 或负值 = 永不过期（仅靠 LRU 容量淘汰）。默认 2 小时——答案会随知识库/时间漂移，
     * 给一个自然新鲜度上限。
     */
    private Duration ttl = Duration.ofHours(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
}
