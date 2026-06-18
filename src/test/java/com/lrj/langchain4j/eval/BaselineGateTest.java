package com.lrj.langchain4j.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 {@link BaselineGate} 的纯判定逻辑：全局门槛 / per-case 门槛 / 缺席 case / 容差 / 基线生成。
 * 不连模型，纯确定性。
 */
class BaselineGateTest {

    private static EvalResult.CaseAggregate agg(String id, double passRate, double avgScore) {
        // attempts 留空，只测聚合字段；CaseAggregate 是 record，直接构造
        return new EvalResult.CaseAggregate(id, "q-" + id, 3,
                (int) Math.round(passRate * 3), passRate, avgScore, 0.0, List.of());
    }

    private static EvalResult.Summary summary(double overallPass, double avgScore,
                                              EvalResult.CaseAggregate... cases) {
        return new EvalResult.Summary(cases.length, 3, cases.length * 3,
                (int) Math.round(overallPass * cases.length * 3), overallPass, avgScore, 1000L, List.of(cases));
    }

    @Test
    void passesWhenAllAboveFloors() {
        EvalResult.Summary s = summary(1.0, 0.9, agg("a", 1.0, 0.9), agg("b", 1.0, 0.85));
        Baseline base = new Baseline(0.9, 0.8, Map.of(
                "a", new Baseline.CaseFloor(0.9, 0.8),
                "b", new Baseline.CaseFloor(0.9, 0.8)));
        BaselineGate.GateResult r = BaselineGate.evaluate(s, base);
        assertThat(r.passed()).isTrue();
        assertThat(r.regressions()).isEmpty();
    }

    @Test
    void failsOnGlobalPassRateRegression() {
        EvalResult.Summary s = summary(0.7, 0.9, agg("a", 0.7, 0.9));
        Baseline base = new Baseline(0.9, 0.8, Map.of());
        BaselineGate.GateResult r = BaselineGate.evaluate(s, base);
        assertThat(r.passed()).isFalse();
        assertThat(r.regressions()).anyMatch(x -> x.contains("overall passRate"));
    }

    @Test
    void failsOnPerCaseAvgScoreRegression() {
        EvalResult.Summary s = summary(1.0, 0.9, agg("a", 1.0, 0.5));
        Baseline base = new Baseline(0.8, 0.6, Map.of("a", new Baseline.CaseFloor(0.9, 0.8)));
        BaselineGate.GateResult r = BaselineGate.evaluate(s, base);
        assertThat(r.passed()).isFalse();
        assertThat(r.regressions()).anyMatch(x -> x.contains("case 'a' avgScore"));
    }

    @Test
    void failsWhenBaselineCaseMissingFromRun() {
        EvalResult.Summary s = summary(1.0, 0.9, agg("a", 1.0, 0.9));
        Baseline base = new Baseline(0.8, 0.6, Map.of(
                "a", new Baseline.CaseFloor(0.9, 0.8),
                "gone", new Baseline.CaseFloor(0.9, 0.8)));
        BaselineGate.GateResult r = BaselineGate.evaluate(s, base);
        assertThat(r.passed()).isFalse();
        assertThat(r.regressions()).anyMatch(x -> x.contains("'gone'") && x.contains("missing"));
    }

    @Test
    void toleranceAvoidsFloatNoiseFalsePositive() {
        // 观测刚好等于门槛（差一个浮点 ulp）不应判回归
        EvalResult.Summary s = summary(0.75, 0.75, agg("a", 0.75, 0.75));
        Baseline base = new Baseline(0.75, 0.75, Map.of("a", new Baseline.CaseFloor(0.75, 0.75)));
        assertThat(BaselineGate.evaluate(s, base).passed()).isTrue();
    }

    @Test
    void newCaseNotInBaseline_isNotARegression() {
        EvalResult.Summary s = summary(1.0, 0.9, agg("a", 1.0, 0.9), agg("brand-new", 1.0, 0.9));
        Baseline base = new Baseline(0.8, 0.6, Map.of("a", new Baseline.CaseFloor(0.9, 0.8)));
        assertThat(BaselineGate.evaluate(s, base).passed()).isTrue();
    }

    @Test
    void deriveBaseline_subtractsSlackAndClamps() {
        EvalResult.Summary s = summary(1.0, 0.95, agg("a", 1.0, 0.5));
        Baseline base = BaselineGate.deriveBaseline(s, 0.1);
        // 全局：1.0-0.1=0.9，0.95-0.1=0.85
        assertThat(base.minOverallPassRate()).isEqualTo(0.9);
        assertThat(base.minAverageScore()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
        // per-case a：passRate 1.0-0.1=0.9（clamp 不触发），avgScore 0.5-0.1=0.4
        Baseline.CaseFloor a = base.cases().get("a");
        assertThat(a.minPassRate()).isEqualTo(0.9);
        assertThat(a.minAvgScore()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        // 派生出的基线对原 summary 自己一定过（floor = 观测 - slack ≤ 观测）
        assertThat(BaselineGate.evaluate(s, base).passed()).isTrue();
    }
}
