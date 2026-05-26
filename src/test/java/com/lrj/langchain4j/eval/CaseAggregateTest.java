package com.lrj.langchain4j.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 测 multi-run 聚合（round 11）的统计正确性 —— passRate / avgScore / scoreStdev。
 * 关键不变量：用总体标准差（除以 N，不是 N-1），因为是完整样本不是抽样。
 */
class CaseAggregateTest {

    @Test
    void singleAttempt_basicAggregation() {
        var attempts = List.of(
                attempt(0.8, true)
        );
        var agg = EvalResult.CaseAggregate.from("c1", "q", attempts);
        assertThat(agg.runs()).isEqualTo(1);
        assertThat(agg.passedCount()).isEqualTo(1);
        assertThat(agg.passRate()).isEqualTo(1.0);
        assertThat(agg.avgScore()).isEqualTo(0.8);
        assertThat(agg.scoreStdev()).isEqualTo(0.0);
    }

    @Test
    void mixedPassFail_aggregatesCorrectly() {
        var attempts = List.of(
                attempt(1.0, true),
                attempt(0.5, false),
                attempt(0.7, true)
        );
        var agg = EvalResult.CaseAggregate.from("c1", "q", attempts);
        assertThat(agg.runs()).isEqualTo(3);
        assertThat(agg.passedCount()).isEqualTo(2);
        assertThat(agg.passRate()).isCloseTo(2.0 / 3, within(0.001));
        assertThat(agg.avgScore()).isCloseTo((1.0 + 0.5 + 0.7) / 3, within(0.001));
        // 总体标准差（除 N，不是 N-1）：mean=0.7333
        // var = ((1.0-0.733)^2 + (0.5-0.733)^2 + (0.7-0.733)^2) / 3
        //     = (0.0711 + 0.0544 + 0.0011) / 3 ≈ 0.0422
        // stdev = sqrt(0.0422) ≈ 0.2055
        assertThat(agg.scoreStdev()).isCloseTo(0.2055, within(0.01));
    }

    @Test
    void emptyAttempts_zeroValues_noCrash() {
        var agg = EvalResult.CaseAggregate.from("c1", "q", List.of());
        assertThat(agg.runs()).isZero();
        assertThat(agg.passedCount()).isZero();
        assertThat(agg.passRate()).isZero();
        assertThat(agg.avgScore()).isZero();
        assertThat(agg.scoreStdev()).isZero();
    }

    @Test
    void allSameScore_zeroStdev() {
        var attempts = List.of(
                attempt(0.6, true),
                attempt(0.6, true),
                attempt(0.6, true)
        );
        var agg = EvalResult.CaseAggregate.from("c1", "q", attempts);
        assertThat(agg.avgScore()).isEqualTo(0.6);
        assertThat(agg.scoreStdev()).isEqualTo(0.0);
        assertThat(agg.passRate()).isEqualTo(1.0);
    }

    @Test
    void attemptsListPreservedInOrder() {
        var first = attempt(0.1, false);
        var second = attempt(0.9, true);
        var agg = EvalResult.CaseAggregate.from("c1", "q", List.of(first, second));
        assertThat(agg.attempts()).containsExactly(first, second);
    }

    private static EvalResult attempt(double score, boolean passed) {
        return new EvalResult(
                "c1", "q", "a",
                new Judgment(score, true, false, "ok"),
                passed,
                100L);
    }
}
