package com.lrj.langchain4j.eval.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.rag.RagIngestionService;
import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索质量评测器：拿黄金集里每个 query 跑主 RAG 链的 {@code vectorRetriever}，把召回片段的 id 跟标注
 * 相关 id 比，算 Recall@k / Precision@k / MRR / Hit@k（逻辑在纯函数 {@link RetrievalMetrics}）。
 *
 * <p>刻意<strong>不经 LLM</strong> —— 这是跟 {@link com.lrj.langchain4j.eval.EvaluationRunner}（LLM Judge
 * 打生成分）互补的另一层。复用 {@code vectorRetriever}（带租户 + category 的 {@code dynamicFilter}），
 * 量的就是线上检索器的真实召回；换 chunking/embedding/rerank/min-score 后重跑，能把「召回变化」跟
 * 「生成变化」拆开归因。
 *
 * <p>用 {@code vectorRetriever} 而非整条 augmentor：测的是<em>向量召回本身</em>的质量（rerank 之前）——
 * 这正是调 chunking/embedding 时最该看的信号；rerank 是召回<em>之后</em>的精排，另算。
 *
 * <p>前置：文档得先入库且 {@code tenantId} 对得上。{@link #runSet(String, boolean)} 的 {@code ingestFirst}
 * 会先跑一遍 {@code /rag/ingest}（用当前请求线程的 {@link com.lrj.langchain4j.security.TenantContext}，
 * 与检索侧 filter 同租户）。
 */
@Service
public class RetrievalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContentRetriever vectorRetriever;
    private final RagIngestionService ragIngestionService;

    public RetrievalEvaluator(@Qualifier("vectorRetriever") ContentRetriever vectorRetriever,
                              RagIngestionService ragIngestionService) {
        this.vectorRetriever = vectorRetriever;
        this.ragIngestionService = ragIngestionService;
    }

    /**
     * 跑命名黄金集：{@code default} → {@code eval/retrieval-cases.json}；其余 → {@code eval/retrieval-cases-<set>.json}。
     * 集名只允许 {@code [a-z0-9-]}，防路径穿越。{@code ingestFirst=true} 时先入库（同租户）。
     */
    public RetrievalReport.Summary runSet(String set, boolean ingestFirst) throws IOException {
        if (ingestFirst) {
            try {
                int n = ragIngestionService.ingestFromConfiguredDir();
                log.info("retrieval-eval ingested {} documents before run", n);
            } catch (Exception e) {
                log.warn("retrieval-eval ingest failed; recall may be understated", e);
            }
        }
        return run(loadCases(set));
    }

    public RetrievalReport.Summary run(List<RetrievalCase> cases) {
        long start = System.currentTimeMillis();
        List<RetrievalReport.CaseResult> results = new ArrayList<>(cases.size());
        for (RetrievalCase c : cases) {
            results.add(runOne(c));
        }
        int n = cases.size();
        double avgRecall = mean(results, RetrievalReport.CaseResult::recall);
        double avgPrecision = mean(results, RetrievalReport.CaseResult::precision);
        double meanMrr = mean(results, RetrievalReport.CaseResult::mrr);
        double hitRate = n == 0 ? 0.0
                : (double) results.stream().filter(RetrievalReport.CaseResult::hit).count() / n;
        long dur = System.currentTimeMillis() - start;
        return new RetrievalReport.Summary(n, avgRecall, avgPrecision, meanMrr, hitRate, dur, results);
    }

    private RetrievalReport.CaseResult runOne(RetrievalCase c) {
        long start = System.currentTimeMillis();
        List<String> retrievedIds = new ArrayList<>();
        try {
            List<Content> hits = vectorRetriever.retrieve(Query.from(c.question()));
            for (int i = 0; i < hits.size(); i++) {
                TextSegment seg = hits.get(i).textSegment();
                retrievedIds.add(TaggedSourceContentInjector.inferId(seg, i));
            }
        } catch (Exception e) {
            log.error("retrieval-eval case {} threw", c.id(), e);
        }
        RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(retrievedIds, c.relevantDocIds());
        long dur = System.currentTimeMillis() - start;
        log.info("retrieval case {}: recall={} precision={} mrr={} hit={} ({}/{} relevant, {} retrieved)",
                c.id(), fmt(m.recall()), fmt(m.precision()), fmt(m.mrr()), m.hit(),
                m.relevantRetrieved(), m.relevantTotal(), m.retrievedTotal());
        return new RetrievalReport.CaseResult(c.id(), c.question(), retrievedIds,
                c.relevantDocIds(), m.recall(), m.precision(), m.mrr(), m.hit(), dur);
    }

    List<RetrievalCase> loadCases(String set) throws IOException {
        String file;
        if (set == null || set.isBlank() || "default".equalsIgnoreCase(set)) {
            file = "eval/retrieval-cases.json";
        } else {
            String safe = set.trim().toLowerCase();
            if (!safe.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("Invalid set name: " + set + " (allowed: [a-z0-9-]+)");
            }
            file = "eval/retrieval-cases-" + safe + ".json";
        }
        try (var in = new ClassPathResource(file).getInputStream()) {
            return JSON.readValue(in, new TypeReference<>() {});
        }
    }

    private static double mean(List<RetrievalReport.CaseResult> rs,
                               java.util.function.ToDoubleFunction<RetrievalReport.CaseResult> f) {
        return rs.isEmpty() ? 0.0 : rs.stream().mapToDouble(f).average().orElse(0.0);
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
