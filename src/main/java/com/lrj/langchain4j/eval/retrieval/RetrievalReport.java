package com.lrj.langchain4j.eval.retrieval;

import java.util.List;

/**
 * 检索质量评测结果。{@link CaseResult} 是每个 query 的检索 id + 标注 id + 四个指标（便于人肉看哪条召回差），
 * {@link Summary} 是跨 case 的宏平均（macro-average，每个 case 权重相同）。
 */
public final class RetrievalReport {

    private RetrievalReport() {}

    public record CaseResult(
            String id,
            String question,
            List<String> retrievedIds,
            List<String> relevantIds,
            double recall,
            double precision,
            double mrr,
            boolean hit,
            long durationMs) {}

    /**
     * @param avgRecall    宏平均 Recall@k —— 最该盯的召回指标（相关文档被捞回的比例）
     * @param avgPrecision 宏平均 Precision@k（召回的里有多少是相关的，反映噪声）
     * @param meanMrr      Mean Reciprocal Rank（第一个相关命中的排名倒数，反映排序质量）
     * @param hitRate      至少命中一个相关文档的 case 比例（最宽松的"能不能用"底线）
     */
    public record Summary(
            int cases,
            double avgRecall,
            double avgPrecision,
            double meanMrr,
            double hitRate,
            long totalDurationMs,
            List<CaseResult> results) {}
}
