package com.lrj.langchain4j.eval.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 测 {@link RetrievalMetrics} 的确定性 IR 指标：文件级/精确级匹配、Recall/Precision/MRR/Hit、
 * 空集边界、rank 敏感性。
 */
class RetrievalMetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void fileLevelGolden_matchesAnyChunkOfThatFile() {
        // 标注文件级 "faq.md"，检索回 faq.md#2 → 命中
        assertThat(RetrievalMetrics.matches("faq.md#2", "faq.md")).isTrue();
        assertThat(RetrievalMetrics.matches("other.md#0", "faq.md")).isFalse();
    }

    @Test
    void exactGolden_requiresFullIdMatch() {
        assertThat(RetrievalMetrics.matches("faq.md#2", "faq.md#2")).isTrue();
        // 精确级标注 "#2"，检索回 "#3" → 不命中（即便同文件）
        assertThat(RetrievalMetrics.matches("faq.md#3", "faq.md#2")).isFalse();
    }

    @Test
    void perfectRetrieval_allOnes() {
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("faq.md#0", "faq.md#1"), List.of("faq.md"));
        assertThat(m.recall()).isCloseTo(1.0, offset(EPS));   // 唯一相关文件被召回
        assertThat(m.precision()).isCloseTo(1.0, offset(EPS)); // 两条都命中
        assertThat(m.mrr()).isCloseTo(1.0, offset(EPS));       // 第 1 条就命中
        assertThat(m.hit()).isTrue();
    }

    @Test
    void partialRecall_multipleRelevantFiles() {
        // 两个相关文件，只召回一个 → recall=0.5
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("a.md#0"), List.of("a.md", "b.md"));
        assertThat(m.recall()).isCloseTo(0.5, offset(EPS));
        assertThat(m.relevantRetrieved()).isEqualTo(1);
        assertThat(m.relevantTotal()).isEqualTo(2);
    }

    @Test
    void precisionReflectsNoise() {
        // 4 条召回里只有 1 条相关 → precision=0.25，但 recall 满（唯一相关文件被召回）
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("junk1.md#0", "a.md#0", "junk2.md#0", "junk3.md#0"), List.of("a.md"));
        assertThat(m.precision()).isCloseTo(0.25, offset(EPS));
        assertThat(m.recall()).isCloseTo(1.0, offset(EPS));
    }

    @Test
    void mrr_isRankSensitive() {
        // 相关命中排在第 3 位 → MRR=1/3
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("junk1.md#0", "junk2.md#0", "a.md#0"), List.of("a.md"));
        assertThat(m.mrr()).isCloseTo(1.0 / 3.0, offset(EPS));
    }

    @Test
    void noRelevantRetrieved_allZero() {
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("junk.md#0"), List.of("a.md"));
        assertThat(m.recall()).isEqualTo(0.0);
        assertThat(m.precision()).isEqualTo(0.0);
        assertThat(m.mrr()).isEqualTo(0.0);
        assertThat(m.hit()).isFalse();
    }

    @Test
    void emptyRetrieval_zeroButNoCrash() {
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(List.of(), List.of("a.md"));
        assertThat(m.recall()).isEqualTo(0.0);
        assertThat(m.hit()).isFalse();
        assertThat(m.retrievedTotal()).isEqualTo(0);
    }

    @Test
    void duplicateGolden_dedupedInDenominator() {
        // 同一文件标注两次不该把 recall 分母抬成 2
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(
                List.of("a.md#0"), List.of("a.md", "a.md"));
        assertThat(m.relevantTotal()).isEqualTo(1);
        assertThat(m.recall()).isCloseTo(1.0, offset(EPS));
    }
}
