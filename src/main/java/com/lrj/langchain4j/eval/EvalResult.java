package com.lrj.langchain4j.eval;

import java.util.List;

/**
 * 单次 (case, run) 调用的结果。{@link Summary} 把同一 case 的 N 次包成 {@link CaseAggregate}。
 */
public record EvalResult(
        String caseId,
        String question,
        String answer,
        Judgment judgment,
        boolean passed,
        long durationMs
) {

    /**
     * 同一 case 跑 N 次的聚合结果。
     *
     * <p>{@code passRate} = passedCount / runs，{@code scoreStdev} 用总体标准差（除以 N，
     * 不是 N-1，因为我们处理的是这次实验的完整样本，不是从更大母体抽样）。
     */
    public record CaseAggregate(
            String caseId,
            String question,
            int runs,
            int passedCount,
            double passRate,
            double avgScore,
            double scoreStdev,
            List<EvalResult> attempts
    ) {
        public static CaseAggregate from(String caseId, String question, List<EvalResult> attempts) {
            int n = attempts.size();
            int passed = (int) attempts.stream().filter(EvalResult::passed).count();
            double mean = attempts.stream().mapToDouble(r -> r.judgment().score()).average().orElse(0.0);
            double var = attempts.stream()
                    .mapToDouble(r -> {
                        double d = r.judgment().score() - mean;
                        return d * d;
                    })
                    .average().orElse(0.0);
            double stdev = Math.sqrt(var);
            return new CaseAggregate(caseId, question, n, passed,
                    n == 0 ? 0.0 : (double) passed / n,
                    mean, stdev, attempts);
        }
    }

    /**
     * 整次评测的汇总。{@code overallPassRate} 把所有 (case, run) 当成独立 trial 算通过率
     * （比"每个 case 通过率再平均"更直观，能反映总尝试数权重）。
     */
    public record Summary(
            int totalCases,
            int runsPerCase,
            int totalRuns,
            int passedRuns,
            double overallPassRate,
            double averageScore,
            long totalDurationMs,
            List<CaseAggregate> cases
    ) {}
}
