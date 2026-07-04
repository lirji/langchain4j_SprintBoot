package com.lrj.langchain4j.cache.semantic;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义响应缓存：把"意思等价但字面不同"的重复提问归并，命中即返回历史答案、<strong>0 LLM token</strong>。
 *
 * <p>装配条件化在 {@code app.cache.semantic.enabled=true}，默认关（Bean 根本不存在）。调用方经
 * {@code ObjectProvider<SemanticCache>} 软依赖接入——关闭时 {@code getIfAvailable()==null}，对话链零回归。
 *
 * <h3>隔离</h3>
 * 按 {@link TenantContext#current()} 的 {@code tenantId} 分桶，一个租户一份独立缓存——A 租户的答案
 * 绝不会命中到 B 租户的提问（跟 {@code ChatMemory} 的 tenant 前缀隔离同思路）。每桶容量上限
 * {@code maxEntries}（<b>每租户</b>，非全局），超出按 LRU 淘汰。
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>{@link #lookup(String)}：embed query → 扫本租户桶算 cosine → 取最高分，{@code >= threshold}
 *       即命中（顺手剔除扫到的过期条目）。命中打 {@code cache.semantic{result=hit}}、返回答案；
 *       否则打 {@code result=miss}、返回 empty。</li>
 *   <li>{@link #put(String, String)}：调用方跑完模型后回填 (query 向量 → answer)。</li>
 * </ol>
 * lookup 的 embed 复用已存的 query 向量（miss 时 put 会重新 embed 一次；为简单起见 lookup/put 各 embed
 * 一次，一次 embedding 调用远比一次 chat completion 便宜）。
 *
 * <h3>线程安全</h3>
 * 每租户桶用 access-order {@link LinkedHashMap} 承载（读写都在桶级锁内），既是 LRU 又天然串行化。
 */
@Component
@ConditionalOnProperty(name = "app.cache.semantic.enabled", havingValue = "true")
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final EmbeddingModel embeddingModel;
    private final SemanticCacheProperties props;
    private final MeterRegistry registry;
    private final Clock clock;

    /** tenantId → 该租户的有界 LRU 桶。 */
    private final Map<String, TenantBucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public SemanticCache(EmbeddingModel embeddingModel,
                         SemanticCacheProperties props,
                         MeterRegistry registry) {
        this(embeddingModel, props, registry, Clock.systemUTC());
    }

    /** 测试用：注入可控 {@link Clock} 以确定性地验证 TTL 过期。 */
    SemanticCache(EmbeddingModel embeddingModel,
                  SemanticCacheProperties props,
                  MeterRegistry registry,
                  Clock clock) {
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 查缓存。命中返回历史答案（0 token），未命中返回 {@link Optional#empty()}。
     * 空白 query 直接判 miss（不 embed）。
     */
    public Optional<String> lookup(String query) {
        if (query == null || query.isBlank()) {
            return miss();
        }
        Embedding qv;
        try {
            qv = embeddingModel.embed(query).content();
        } catch (Exception e) {
            // embedding 后端故障：降级为 miss，绝不因缓存层拖垮对话主链
            log.warn("semantic cache: embed failed, treating as miss", e);
            return miss();
        }
        TenantBucket bucket = buckets.get(tenant());
        if (bucket == null) {
            return miss();
        }
        String answer = bucket.findBestHit(qv, props.getThreshold(), now(), ttlMillis());
        if (answer != null) {
            registry.counter("cache.semantic", "result", "hit").increment();
            return Optional.of(answer);
        }
        return miss();
    }

    /**
     * 回填缓存：调用方跑完模型后把 (query, answer) 存入本租户桶。query/answer 任一空白则跳过。
     */
    public void put(String query, String answer) {
        if (query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        Embedding qv;
        try {
            qv = embeddingModel.embed(query).content();
        } catch (Exception e) {
            log.warn("semantic cache: embed failed, skip put", e);
            return;
        }
        buckets.computeIfAbsent(tenant(), k -> new TenantBucket(props.getMaxEntries()))
                .put(query, new Entry(qv, answer, now()));
    }

    /** PII / 合规：清空当前租户的缓存，返回清掉的条数。 */
    public int clearCurrentTenant() {
        TenantBucket bucket = buckets.remove(tenant());
        return bucket == null ? 0 : bucket.size();
    }

    private Optional<String> miss() {
        registry.counter("cache.semantic", "result", "miss").increment();
        return Optional.empty();
    }

    private static String tenant() {
        return TenantContext.current().tenantId();
    }

    private long now() {
        return clock.millis();
    }

    /** TTL 毫秒；<=0 表示永不过期。 */
    private long ttlMillis() {
        return props.getTtl() == null ? 0L : props.getTtl().toMillis();
    }

    /** 一条缓存记录：query 向量 + 答案 + 写入时刻。 */
    private record Entry(Embedding embedding, String answer, long createdAtMillis) {}

    /**
     * 单租户有界 LRU 桶。用 access-order {@link LinkedHashMap} 承载：命中/写入即"访问"→ 移到尾部，
     * 淘汰时从头部（最久未使用）删。key = 归一化 query 文本，天然对完全相同的字面去重。
     * 所有读写都在 {@code synchronized(this)} 内串行，避免并发遍历/结构改动。
     */
    private static final class TenantBucket {
        private final int maxEntries;
        private final LinkedHashMap<String, Entry> map;

        TenantBucket(int maxEntries) {
            this.maxEntries = Math.max(1, maxEntries);
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > TenantBucket.this.maxEntries;
                }
            };
        }

        synchronized void put(String query, Entry entry) {
            map.put(norm(query), entry);
        }

        synchronized int size() {
            return map.size();
        }

        /**
         * 扫全桶找 cosine 最高且 {@code >= threshold} 的未过期条目，顺手剔除遍历中遇到的过期条目。
         * 命中则把该 key {@code get} 一次（access-order 下等于"提升"到尾部，实现 LRU），返回答案。
         */
        synchronized String findBestHit(Embedding qv, double threshold, long now, long ttlMillis) {
            String bestKey = null;
            double bestSim = threshold;   // 只接受 >= threshold 的
            Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> e = it.next();
                Entry entry = e.getValue();
                if (ttlMillis > 0 && now - entry.createdAtMillis() > ttlMillis) {
                    it.remove();   // 过期：清除，不参与匹配
                    continue;
                }
                double sim = CosineSimilarity.between(qv, entry.embedding());
                if (sim >= bestSim) {
                    bestSim = sim;
                    bestKey = e.getKey();
                }
            }
            if (bestKey == null) {
                return null;
            }
            Entry hit = map.get(bestKey);   // access-order：提升到尾部 = LRU 保鲜
            return hit == null ? null : hit.answer();
        }

        private static String norm(String s) {
            return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
