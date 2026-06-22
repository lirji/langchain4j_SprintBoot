package com.lrj.langchain4j.rag.graph;

import com.lrj.langchain4j.rag.CategoryContext;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GraphRAG 的检索路：query 实体链接 → {@link GraphStore#neighbors N 跳遍历} → 把连通的三元组
 * 序列化成可读关系陈述，作为第三路 {@link ContentRetriever} 并联进 {@code DefaultQueryRouter}
 * （vector / keyword / <strong>graph</strong>），RRF 在 aggregator 里白嫖融合。
 *
 * <p><strong>provenance 是设计核心</strong>：返回结果<strong>按 sourceId 分组</strong>，每组一个
 * {@link Content}，其 {@link TextSegment} 的 metadata 写回 {@code file_name}/{@code index}，
 * 让 {@code TaggedSourceContentInjector.inferId} 重建出原始 {@code file#chunk} id。这样图召回的片段
 * 在引用层跟向量召回完全一致 —— {@code [doc=ID]} 引用、{@code RetrievedSourcesContext}、
 * grounding Layer 0 引用核对全部白嫖。租户/类别隔离照搬 {@code KeywordContentRetriever}。
 */
public class GraphContentRetriever implements ContentRetriever {

    private final GraphStore graphStore;
    private final EntityLinker entityLinker;
    private final int maxHops;
    private final int maxTriples;

    public GraphContentRetriever(GraphStore graphStore, EntityLinker entityLinker, int maxHops, int maxTriples) {
        this.graphStore = graphStore;
        this.entityLinker = entityLinker;
        this.maxHops = maxHops;
        this.maxTriples = maxTriples;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String tenant = TenantContext.current().tenantId();
        String category = CategoryContext.get();

        Set<String> seeds = entityLinker.link(query.text(), tenant, category);
        if (seeds.isEmpty()) return List.of();          // 没命中实体 → 这一路空手，交给向量/keyword 路

        List<Triple> triples = graphStore.neighbors(seeds, maxHops, tenant, category);
        if (triples.isEmpty()) return List.of();
        if (triples.size() > maxTriples) triples = triples.subList(0, maxTriples);   // 挡高连通实体 context 爆炸

        // 按 sourceId 分组（保 hop 顺序）—— 每个原始 chunk 一个 Content，引用层与向量召回对齐
        Map<String, List<Triple>> bySource = new LinkedHashMap<>();
        for (Triple t : triples) {
            String src = (t.sourceId() == null || t.sourceId().isBlank()) ? "graph#0" : t.sourceId();
            bySource.computeIfAbsent(src, k -> new ArrayList<>()).add(t);
        }

        List<Content> out = new ArrayList<>(bySource.size());
        for (Map.Entry<String, List<Triple>> e : bySource.entrySet()) {
            String text = e.getValue().stream().map(GraphContentRetriever::statement).collect(Collectors.joining("\n"));
            out.add(Content.from(TextSegment.from(text, sourceMetadata(e.getKey(), tenant, category))));
        }
        return out;
    }

    /** "张三 —隶属于→ 华东大区" —— 人类可读关系行，让 LLM 既能用关系也能溯源。 */
    private static String statement(Triple t) {
        return t.subject() + " —" + t.relation() + "→ " + t.object();
    }

    /** 把 sourceId（{@code file#idx}）拆回 file_name + index，让 inferId 重建出同一个引用 id。 */
    private static Metadata sourceMetadata(String sourceId, String tenant, String category) {
        Map<String, String> m = new java.util.HashMap<>();
        int hash = sourceId.lastIndexOf('#');
        if (hash > 0) {
            m.put("file_name", sourceId.substring(0, hash));
            m.put("index", sourceId.substring(hash + 1));
        } else {
            m.put("file_name", sourceId);
        }
        m.put("tenantId", tenant);
        if (category != null) m.put("category", category);
        return Metadata.from(m);
    }
}
