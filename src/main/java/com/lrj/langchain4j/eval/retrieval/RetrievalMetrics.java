package com.lrj.langchain4j.eval.retrieval;

import java.util.List;

/**
 * 纯函数：把「检索回的有序 id 列表」+「标注的相关 id 集合」算成经典 IR 指标 ——
 * Recall@k / Precision@k / MRR / Hit@k。无状态、可确定性单测（{@code RetrievalMetricsTest}）。
 *
 * <p>这补的是 {@code eval-cases.json} 那套 passRate（规则匹配 + LLM Judge）一直没覆盖的<strong>召回层</strong>：
 * 前者测「答对没」（生成质量，混了检索 + LLM 两层），本类测「相关文档有没有被捞回来」（<em>纯</em>检索质量，
 * 不经 LLM）。调 chunking / embedding / rerank / min-score 后能量化到底是召回变好还是生成变好。
 * 详见 {@code docs/recall-verification.md} 里 passRate vs Recall@k 的辨析。
 *
 * <p><b>id 匹配规则</b>（对 chunk 切分漂移鲁棒）：标注 id 分两种粒度 ——
 * <ul>
 *   <li><b>文件级</b>（不含 {@code #}，如 {@code project-faq.md}）：检索 id 的文件部分（{@code #} 前）相等即算命中。
 *       换 chunk 策略后片段号会变，但「该来自哪个文件」不变，所以文件级标注最稳，是推荐默认。</li>
 *   <li><b>精确级</b>（含 {@code #}，如 {@code project-faq.md#2}）：检索 id 全等才算命中。用于必须钉住具体 section 的严格 case。</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /** 单 case 的检索指标。recall/precision/mrr ∈ [0,1]；hit = 是否至少命中一个相关文档。 */
    public record CaseMetrics(double recall, double precision, double mrr, boolean hit,
                              int relevantTotal, int relevantRetrieved, int retrievedTotal) {}

    /**
     * 判断一个检索回的 id 是否命中某个标注 id。见类注释匹配规则。
     */
    public static boolean matches(String retrievedId, String relevantId) {
        if (retrievedId == null || relevantId == null) return false;
        if (retrievedId.equals(relevantId)) return true;
        if (relevantId.indexOf('#') < 0) {
            // 文件级标注：比检索 id 的文件部分
            return filePart(retrievedId).equals(relevantId);
        }
        return false;
    }

    /** {@code file.md#3} → {@code file.md}；无 {@code #} 时原样返回。 */
    public static String filePart(String id) {
        if (id == null) return "";
        int h = id.indexOf('#');
        return h < 0 ? id : id.substring(0, h);
    }

    /**
     * 算一个 case 的四个指标。
     *
     * @param retrievedIds 检索器回的 id，<strong>按相关性降序</strong>（rank 敏感的 MRR 依赖顺序）
     * @param relevantIds  该 query 的标注相关 id（去重后当分母）
     */
    public static CaseMetrics compute(List<String> retrievedIds, List<String> relevantIds) {
        List<String> retrieved = retrievedIds == null ? List.of() : retrievedIds;
        // 相关标注去重当分母 —— 同一文件重复标注不该抬高 recall 分母
        List<String> relevant = relevantIds == null ? List.of()
                : relevantIds.stream().distinct().toList();

        int relevantTotal = relevant.size();
        int retrievedTotal = retrieved.size();

        // recall 分子：有多少个「标注相关」被至少一个检索 id 命中
        int covered = 0;
        for (String g : relevant) {
            boolean anyHit = retrieved.stream().anyMatch(r -> matches(r, g));
            if (anyHit) covered++;
        }
        double recall = relevantTotal == 0 ? 0.0 : (double) covered / relevantTotal;

        // precision 分子：有多少个检索 id 命中了任一标注相关
        int hitRetrieved = 0;
        int firstHitRank = -1;
        for (int i = 0; i < retrieved.size(); i++) {
            final String r = retrieved.get(i);
            boolean isRel = relevant.stream().anyMatch(g -> matches(r, g));
            if (isRel) {
                hitRetrieved++;
                if (firstHitRank < 0) firstHitRank = i + 1; // 1-based
            }
        }
        double precision = retrievedTotal == 0 ? 0.0 : (double) hitRetrieved / retrievedTotal;
        double mrr = firstHitRank < 0 ? 0.0 : 1.0 / firstHitRank;
        boolean hit = covered > 0;

        return new CaseMetrics(recall, precision, mrr, hit,
                relevantTotal, covered, retrievedTotal);
    }
}
