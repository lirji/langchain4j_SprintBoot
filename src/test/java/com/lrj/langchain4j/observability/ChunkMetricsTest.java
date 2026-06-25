package com.lrj.langchain4j.observability;

import dev.langchain4j.data.segment.TextSegment;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分质量打点的确定性行为：尺寸分布 summary / 碎块·超大块计数 / strategy tag / 空集与零计数不打。
 * 用 SimpleMeterRegistry，不连模型、不起 Spring。
 */
class ChunkMetricsTest {

    private static TextSegment seg(int len) {
        return TextSegment.from("x".repeat(len));
    }

    @Test
    void recordsSizeDistributionAndBuckets() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(reg, 50, 2000);
        Tags t = Tags.of("strategy", "recursive");

        // 30(碎) / 300(正常) / 3000(超大)
        metrics.record("recursive", 2, List.of(seg(30), seg(300), seg(3000)));

        assertThat(reg.get("rag.ingest.documents").tags(t).counter().count()).isEqualTo(2.0);
        assertThat(reg.get("rag.chunk.total").tags(t).counter().count()).isEqualTo(3.0);
        assertThat(reg.get("rag.chunk.tiny").tags(t).counter().count()).isEqualTo(1.0);
        assertThat(reg.get("rag.chunk.oversize").tags(t).counter().count()).isEqualTo(1.0);

        var summary = reg.get("rag.chunk.size").tags(t).summary();
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.totalAmount()).isEqualTo(30 + 300 + 3000);
        assertThat(summary.max()).isEqualTo(3000);
    }

    @Test
    void tagsByStrategySeparately() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(reg, 50, 2000);

        metrics.record("recursive", 1, List.of(seg(100)));
        metrics.record("semantic", 1, List.of(seg(800), seg(900)));

        assertThat(reg.get("rag.chunk.total").tags("strategy", "recursive").counter().count()).isEqualTo(1.0);
        assertThat(reg.get("rag.chunk.total").tags("strategy", "semantic").counter().count()).isEqualTo(2.0);
    }

    @Test
    void emptySegmentsStillCountsDocumentsButNoChunkMeters() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(reg, 50, 2000);

        metrics.record("recursive", 3, List.of());

        assertThat(reg.get("rag.ingest.documents").tags("strategy", "recursive").counter().count()).isEqualTo(3.0);
        // 无 chunk → size summary / total 未创建
        assertThat(reg.find("rag.chunk.total").counter()).isNull();
        assertThat(reg.find("rag.chunk.size").summary()).isNull();
    }

    @Test
    void noTinyOrOversizeCountersWhenAllNormal() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(reg, 50, 2000);

        metrics.record("markdown-header", 1, List.of(seg(200), seg(400)));

        assertThat(reg.get("rag.chunk.total").counter().count()).isEqualTo(2.0);
        // 没有碎块/超大块 → 这两个 counter 不创建（保持指标干净）
        assertThat(reg.find("rag.chunk.tiny").counter()).isNull();
        assertThat(reg.find("rag.chunk.oversize").counter()).isNull();
    }

    @Test
    void blankStrategyFallsBackToUnknownTag() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(reg, 50, 2000);

        metrics.record(null, 1, List.of(seg(100)));

        assertThat(reg.get("rag.chunk.total").tags("strategy", "unknown").counter().count()).isEqualTo(1.0);
    }
}
