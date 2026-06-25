package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义切分的确定性行为：用桩 EmbeddingModel（按关键词给固定向量）让相邻句距离可预测，
 * 验证话题边界处切、单话题不切、超长块 fallback、embedding 故障降级、切句与分位算法。
 */
class SemanticChunkingSplitterTest {

    /**
     * 桩：句子含「猫」→ 向量 [1,0]；含「股票」→ [0,1]；否则 [0,0,...] 退化。
     * 同话题 cosine=1（距离 0），跨话题 cosine=0（距离 1）→ 边界处必有断崖。
     */
    private static class KeywordEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> out = new ArrayList<>(segments.size());
            for (TextSegment s : segments) {
                String t = s.text();
                if (t.contains("猫")) out.add(Embedding.from(new float[]{1, 0}));
                else if (t.contains("股票")) out.add(Embedding.from(new float[]{0, 1}));
                else out.add(Embedding.from(new float[]{0.5f, 0.5f}));
            }
            return Response.from(out);
        }
        @Override
        public int dimension() { return 2; }
    }

    /** 抛异常的桩：验证 embedding 后端故障时降级 recursive，不让入库崩。 */
    private static class FailingEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            throw new RuntimeException("embedding backend down");
        }
        @Override
        public int dimension() { return 2; }
    }

    private SemanticChunkingSplitter splitter(EmbeddingModel model, int maxSize, double percentile) {
        return new SemanticChunkingSplitter(model, 0, percentile, maxSize, 0,
                DocumentSplitters.recursive(maxSize, 0), null);
    }

    private static String topic(String keyword, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("这是关于").append(keyword).append("的第").append(i).append("句话。");
        }
        return sb.toString();
    }

    @Test
    void cutsAtTopicBoundary() {
        // 20 句猫 + 20 句股票：39 个间隙里只有 1 个（边界）距离=1，其余=0 → p95 阈值=0 → 边界处切 → 2 块
        String text = topic("猫", 20) + topic("股票", 20);
        List<TextSegment> segs = splitter(new KeywordEmbeddingModel(), 100_000, 95).split(Document.from(text));

        assertThat(segs).hasSize(2);
        assertThat(segs.get(0).text()).contains("猫").doesNotContain("股票");
        assertThat(segs.get(1).text()).contains("股票").doesNotContain("猫");
        assertThat(segs.get(0).metadata().getString("index")).isEqualTo("0");
        assertThat(segs.get(1).metadata().getString("index")).isEqualTo("1");
    }

    @Test
    void singleTopicStaysOneChunk() {
        String text = topic("猫", 15);
        List<TextSegment> segs = splitter(new KeywordEmbeddingModel(), 100_000, 95).split(Document.from(text));

        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).text()).contains("猫");
    }

    @Test
    void oversizeSemanticChunkFallsBackToRecursive() {
        // 单话题但远超 maxSize=50 → 1 个语义块被 recursive 再切成多块
        String text = topic("猫", 20);
        List<TextSegment> segs = splitter(new KeywordEmbeddingModel(), 50, 95).split(Document.from(text));

        assertThat(segs.size()).isGreaterThan(1);
        assertThat(segs).allSatisfy(s -> assertThat(s.text()).contains("猫"));
    }

    @Test
    void embeddingFailureFallsBackInsteadOfThrowing() {
        String text = topic("猫", 10);
        List<TextSegment> segs = splitter(new FailingEmbeddingModel(), 60, 95).split(Document.from(text));

        // 没抛异常，降级 recursive 切出 ≥1 块
        assertThat(segs).isNotEmpty();
        assertThat(String.join("", segs.stream().map(TextSegment::text).toList())).contains("猫");
    }

    @Test
    void singleSentenceStaysOneSegment() {
        DocumentSplitter s = splitter(new KeywordEmbeddingModel(), 100_000, 95);
        assertThat(s.split(Document.from("只有一句话。"))).hasSize(1);
    }

    @Test
    void splitSentences_chineseEnglishAndDecimal() {
        List<String> s = SemanticChunkingSplitter.splitSentences("猫很可爱。Dogs run! 价格是 3.5 元；结束");
        // 。 ! ; 各切一句，3.5 的小数点不切
        assertThat(s).containsExactly("猫很可爱。", "Dogs run!", "价格是 3.5 元；", "结束");
    }

    @Test
    void percentile_nearestRank() {
        // 5 个值 {0,0,0,0,1}：nearest-rank → p50 落在第 3 个(=0)，p95/p100 落在最大值(=1)
        double[] d = {0, 0, 0, 0, 1};
        assertThat(SemanticChunkingSplitter.percentile(d, 50)).isEqualTo(0.0);
        assertThat(SemanticChunkingSplitter.percentile(d, 100)).isEqualTo(1.0);
        // 间隙多时 p95 才会落在「常态」之下：40 个值里仅 1 个=1 → p95=0（这正是默认 95 切得稀疏的原因）
        double[] sparse = new double[40];
        sparse[39] = 1.0;
        assertThat(SemanticChunkingSplitter.percentile(sparse, 95)).isEqualTo(0.0);
        assertThat(SemanticChunkingSplitter.percentile(new double[]{}, 95)).isEqualTo(0.0);
    }
}
