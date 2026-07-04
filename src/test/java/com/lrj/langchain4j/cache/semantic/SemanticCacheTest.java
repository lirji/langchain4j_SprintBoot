package com.lrj.langchain4j.cache.semantic;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义缓存的确定性单测：桩 EmbeddingModel（把字符串映射到固定向量，令 cosine 可预测），
 * 不连任何真实模型/网络。覆盖：阈值以上命中 / 阈值以下不命中 / 租户隔离 / LRU 容量淘汰 / TTL 过期 / 指标计数。
 */
class SemanticCacheTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static SemanticCacheProperties props(double threshold, int maxEntries, java.time.Duration ttl) {
        SemanticCacheProperties p = new SemanticCacheProperties();
        p.setEnabled(true);
        p.setThreshold(threshold);
        p.setMaxEntries(maxEntries);
        p.setTtl(ttl);
        return p;
    }

    private static void asTenant(String tenantId) {
        TenantContext.set(new TenantContext.Tenant(tenantId, "u", Set.of()));
    }

    private double hits() { return registry.counter("cache.semantic", "result", "hit").count(); }
    private double misses() { return registry.counter("cache.semantic", "result", "miss").count(); }

    @Test
    void hit_whenSimilarityAboveThreshold() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.map("how to refund", new float[]{1f, 0f, 0f});
        model.map("refund process please", new float[]{1f, 0.05f, 0f}); // cosine ~0.9988 vs above
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, null), registry);

        asTenant("t1");
        assertTrue(cache.lookup("how to refund").isEmpty(), "cold cache misses");
        cache.put("how to refund", "退款请在订单页点『申请退款』");

        Optional<String> hit = cache.lookup("refund process please");
        assertTrue(hit.isPresent(), "semantically-close query should hit");
        assertEquals("退款请在订单页点『申请退款』", hit.get());
        assertEquals(1.0, hits(), 1e-9);
    }

    @Test
    void miss_whenSimilarityBelowThreshold() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.map("how to refund", new float[]{1f, 0f, 0f});
        model.map("what is the weather", new float[]{0f, 1f, 0f}); // cosine 0 vs refund
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, null), registry);

        asTenant("t1");
        cache.put("how to refund", "refund answer");
        assertTrue(cache.lookup("what is the weather").isEmpty(), "orthogonal query must miss");
    }

    @Test
    void tenantIsolation_sameQueryDifferentTenant() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.map("how to refund", new float[]{1f, 0f, 0f});
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, null), registry);

        asTenant("tenantA");
        cache.put("how to refund", "answer for A");

        asTenant("tenantB");
        assertTrue(cache.lookup("how to refund").isEmpty(), "tenant B must not see tenant A's cached answer");

        asTenant("tenantA");
        assertEquals("answer for A", cache.lookup("how to refund").orElseThrow());
    }

    @Test
    void lruEviction_evictsLeastRecentlyUsed() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        // 三个互相正交的向量，彼此不会误命中
        model.map("q1", new float[]{1f, 0f, 0f});
        model.map("q2", new float[]{0f, 1f, 0f});
        model.map("q3", new float[]{0f, 0f, 1f});
        SemanticCache cache = new SemanticCache(model, props(0.95, 2, null), registry);

        asTenant("t1");
        cache.put("q1", "a1");
        cache.put("q2", "a2");
        // 访问 q1 → 提升为最近使用；随后写 q3 应淘汰最久未用的 q2
        assertEquals("a1", cache.lookup("q1").orElseThrow());
        cache.put("q3", "a3");

        assertTrue(cache.lookup("q1").isPresent(), "q1 was recently used, survives");
        assertTrue(cache.lookup("q3").isPresent(), "q3 just inserted, present");
        assertTrue(cache.lookup("q2").isEmpty(), "q2 was least-recently-used, evicted");
    }

    @Test
    void ttlEviction_expiredEntryMisses() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.map("q1", new float[]{1f, 0f, 0f});
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, java.time.Duration.ofSeconds(10)), registry, clock);

        asTenant("t1");
        cache.put("q1", "fresh");
        assertEquals("fresh", cache.lookup("q1").orElseThrow(), "within ttl → hit");

        clock.advanceMillis(11_000); // 超过 10s TTL
        assertTrue(cache.lookup("q1").isEmpty(), "expired entry must miss");
    }

    @Test
    void blankQuery_missesWithoutEmbedding() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, null), registry);

        asTenant("t1");
        assertTrue(cache.lookup("   ").isEmpty());
        assertEquals(0, model.embedCalls, "blank query should not embed");
        assertEquals(1.0, misses(), 1e-9);
    }

    @Test
    void embedFailure_degradesToMiss() {
        StubEmbeddingModel model = new StubEmbeddingModel();
        model.fail = true;
        SemanticCache cache = new SemanticCache(model, props(0.95, 100, null), registry);

        asTenant("t1");
        assertTrue(cache.lookup("anything").isEmpty(), "embedding backend failure must not throw");
        assertFalse(hits() > 0);
    }

    // ---- 桩：字符串 → 固定向量，令 cosine 可预测；未映射的串抛异常以暴露测试遗漏 ----
    private static final class StubEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> vectors = new HashMap<>();
        boolean fail = false;
        int embedCalls = 0;

        void map(String text, float[] vector) { vectors.put(text, vector); }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            embedCalls++;
            if (fail) throw new RuntimeException("boom");
            List<Embedding> out = new ArrayList<>();
            for (TextSegment s : segments) {
                float[] v = vectors.get(s.text());
                if (v == null) throw new IllegalStateException("no stub vector for: " + s.text());
                out.add(Embedding.from(v));
            }
            return Response.from(out);
        }
    }

    // ---- 可变时钟：确定性驱动 TTL 过期 ----
    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant start) { this.instant = start; }
        void advanceMillis(long ms) { instant = instant.plusMillis(ms); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return instant.toEpochMilli(); }
    }
}
