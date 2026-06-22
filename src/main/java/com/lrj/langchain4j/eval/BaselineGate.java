package com.lrj.langchain4j.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次评测 {@link EvalResult.Summary} 跟 {@link Baseline} 对照，判断有没有回归。<strong>纯函数、无 IO</strong>，
 * 所以能被 JUnit 确定性地测（见 {@code BaselineGateTest}）。
 *
 * <p>判定规则（带 {@link #EPS} 容差，避开浮点噪声）：
 * <ol>
 *   <li>整体 passRate / avgScore 低于基线全局门槛 → 回归</li>
 *   <li>基线里某 case 在本次 run 缺席 → 回归（防止「悄悄删 case 让门禁变绿」）</li>
 *   <li>某 case 的 passRate / avgScore 低于其 per-case 门槛 → 回归</li>
 * </ol>
 * 本次新增、基线里没有的 case 不算回归（信息项）。{@code passed = regressions.isEmpty()}。
 */
public final class BaselineGate {

    /** 浮点容差：观测值只要不比门槛低于这个量就算过，避免 0.7499999 < 0.75 这种假回归。 */
    public static final double EPS = 1e-6;

    private BaselineGate() {}

    /** 门禁结果：是否通过 + 回归项明细（人类可读）。 */
    public record GateResult(boolean passed, List<String> regressions, EvalResult.Summary summary) {}

    public static GateResult evaluate(EvalResult.Summary summary, Baseline baseline) {
        List<String> regressions = new ArrayList<>();

        if (summary.overallPassRate() + EPS < baseline.minOverallPassRate()) {
            regressions.add(String.format("overall passRate %.4f < baseline %.4f",
                    summary.overallPassRate(), baseline.minOverallPassRate()));
        }
        if (summary.averageScore() + EPS < baseline.minAverageScore()) {
            regressions.add(String.format("overall avgScore %.4f < baseline %.4f",
                    summary.averageScore(), baseline.minAverageScore()));
        }

        Map<String, EvalResult.CaseAggregate> byId = new HashMap<>();
        for (EvalResult.CaseAggregate agg : summary.cases()) {
            byId.put(agg.caseId(), agg);
        }

        for (Map.Entry<String, Baseline.CaseFloor> e : baseline.safeCases().entrySet()) {
            String id = e.getKey();
            Baseline.CaseFloor floor = e.getValue();
            EvalResult.CaseAggregate agg = byId.get(id);
            if (agg == null) {
                regressions.add("case '" + id + "' present in baseline but missing from run");
                continue;
            }
            if (agg.passRate() + EPS < floor.minPassRate()) {
                regressions.add(String.format("case '%s' passRate %.4f < baseline %.4f",
                        id, agg.passRate(), floor.minPassRate()));
            }
            if (agg.avgScore() + EPS < floor.minAvgScore()) {
                regressions.add(String.format("case '%s' avgScore %.4f < baseline %.4f",
                        id, agg.avgScore(), floor.minAvgScore()));
            }
        }

        return new GateResult(regressions.isEmpty(), regressions, summary);
    }

    /**
     * 从一次实测 run 生成基线：每个门槛 = 观测值 − {@code slack}（clamp 到 [0,1]）。
     * {@code slack} 给 Assistant temp=0.7 的正常抖动留空间（推荐 0.1，配合 {@code runs>=3} 的稳定观测）。
     */
    public static Baseline deriveBaseline(EvalResult.Summary summary, double slack) {
        Map<String, Baseline.CaseFloor> cases = new HashMap<>();
        for (EvalResult.CaseAggregate agg : summary.cases()) {
            cases.put(agg.caseId(), new Baseline.CaseFloor(
                    clamp(agg.passRate() - slack), clamp(agg.avgScore() - slack)));
        }
        return new Baseline(
                clamp(summary.overallPassRate() - slack),
                clamp(summary.averageScore() - slack),
                cases);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
