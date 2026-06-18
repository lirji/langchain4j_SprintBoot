package com.lrj.langchain4j.rag.graph;

import com.lrj.langchain4j.rag.CategoryContext;
import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import com.lrj.langchain4j.rag.hybrid.SimpleKeywordTokenizer;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphRAG 确定性逻辑单测（不连模型）：图遍历 / 租户·类别隔离 / 实体链接 / 检索 provenance。
 * 抽取质量这类需连模型的断言走 eval（{@code eval-cases-graph.json}），不在这里。
 */
class GraphRagTest {

    private static final String T1 = "t1";
    private static final String T2 = "t2";

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CategoryContext.clear();
    }

    private static Triple t(String s, String r, String o, String src, String tenant, String cat) {
        return new Triple(s, r, o, src, tenant, cat);
    }

    private InMemoryGraphStore orgGraph() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                t("张三", "隶属于", "李四", "org.md#0", T1, null),
                t("李四", "负责", "华东大区", "org.md#1", T1, null),
                t("张三", "处理", "订单#1001", "org.md#2", T1, null),
                t("张三", "处理", "订单#1002", "org.md#2", T1, null)));
        return g;
    }

    // ---------- store: traversal ----------

    @Test
    void neighbors_oneHop_returnsDirectEdgesOnly() {
        List<Triple> n = orgGraph().neighbors(Set.of("张三"), 1, T1, null);
        assertThat(n).extracting(Triple::object)
                .containsExactlyInAnyOrder("李四", "订单#1001", "订单#1002");
        // 两跳外的 李四→华东大区 不该在 1 跳内出现
        assertThat(n).noneMatch(x -> x.object().equals("华东大区"));
    }

    @Test
    void neighbors_twoHops_bridgesAcrossChunks() {
        List<Triple> n = orgGraph().neighbors(Set.of("张三"), 2, T1, null);
        // 张三 →(1跳) 李四 →(2跳) 华东大区，跨 chunk 的关系链被连起来
        assertThat(n).anyMatch(x -> x.subject().equals("李四") && x.object().equals("华东大区"));
    }

    @Test
    void neighbors_bothEndpointsSeeded_connectsViaBridgeAtOneHop() {
        // query 同时含「张三」和「华东大区」时，1 跳即可让模型看到 张三→李四 和 李四→华东大区
        List<Triple> n = orgGraph().neighbors(Set.of("张三", "华东大区"), 1, T1, null);
        assertThat(n).anyMatch(x -> x.subject().equals("张三") && x.object().equals("李四"));
        assertThat(n).anyMatch(x -> x.subject().equals("李四") && x.object().equals("华东大区"));
    }

    @Test
    void neighbors_seedAsObject_returnsSubjectSide() {
        // 种子是关系的客体（李四 是「张三 隶属于 李四」的 object）也要能遍历到另一端
        List<Triple> n = orgGraph().neighbors(Set.of("李四"), 1, T1, null);
        assertThat(n).anyMatch(x -> x.subject().equals("张三") && x.object().equals("李四"));
    }

    @Test
    void neighbors_tenantIsolation_doesNotTraverseOtherTenant() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                t("张三", "隶属于", "李四", "a.md#0", T1, null),
                t("张三", "隶属于", "王五", "b.md#0", T2, null)));   // 同名实体、不同租户
        List<Triple> n = g.neighbors(Set.of("张三"), 2, T1, null);
        assertThat(n).hasSize(1);
        assertThat(n.get(0).object()).isEqualTo("李四");          // 绝不串到 T2 的边
    }

    @Test
    void neighbors_categoryFilter() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                t("X", "rel", "A", "s#0", T1, "hr"),
                t("X", "rel", "B", "s#1", T1, "finance")));
        assertThat(g.neighbors(Set.of("X"), 1, T1, "hr")).extracting(Triple::object).containsExactly("A");
        assertThat(g.neighbors(Set.of("X"), 1, T1, null)).hasSize(2);   // null = 不限类别
    }

    @Test
    void removeBySourcePrefix_purgesAndRebuildsIndex() {
        InMemoryGraphStore g = orgGraph();
        int removed = g.removeBySourcePrefix(T1, "org.md#2");
        assertThat(removed).isEqualTo(2);
        assertThat(g.size()).isEqualTo(2);
        // 索引已重建：张三 现在只剩 隶属于 李四 这条边
        assertThat(g.neighbors(Set.of("张三"), 1, T1, null)).extracting(Triple::object).containsExactly("李四");
    }

    @Test
    void removeBySourcePrefix_respectsTenant() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                t("A", "r", "B", "doc.md#0", T1, null),
                t("A", "r", "B", "doc.md#0", T2, null)));   // 同 source 不同租户
        assertThat(g.removeBySourcePrefix(T1, "doc.md#")).isEqualTo(1);
        assertThat(g.size()).isEqualTo(1);                  // T2 的边不动
    }

    // ---------- entity linking ----------

    @Test
    void tokenLinker_substringContainment_andTenantScope() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                t("华东大区", "含", "上海仓", "s#0", T1, null),
                t("华北大区", "含", "北京仓", "s#0", T2, null)));   // 另一租户的实体不该被链接
        TokenEntityLinker linker = new TokenEntityLinker(g, new SimpleKeywordTokenizer());
        Set<String> seeds = linker.link("华东大区现在归谁管？", T1, null);
        assertThat(seeds).contains("华东大区");
        assertThat(seeds).doesNotContain("华北大区");
    }

    @Test
    void tokenLinker_noEntityInQuery_returnsEmpty() {
        TokenEntityLinker linker = new TokenEntityLinker(orgGraph(), new SimpleKeywordTokenizer());
        assertThat(linker.link("今天天气怎么样？", T1, null)).isEmpty();
    }

    // ---------- retriever: provenance + grouping ----------

    @Test
    void retriever_reconstructsSourceIdSoCitationLayerMatchesVector() {
        TenantContext.set(new TenantContext.Tenant(T1, "u", Set.of()));
        GraphContentRetriever r = new GraphContentRetriever(
                orgGraph(), new TokenEntityLinker(orgGraph(), new SimpleKeywordTokenizer()), 1, 30);

        List<Content> contents = r.retrieve(Query.from("张三是谁的下属？"));
        assertThat(contents).isNotEmpty();
        TextSegment seg = contents.get(0).textSegment();
        // inferId 必须能从 metadata 重建出原始 file#chunk —— 这是 [doc=ID] 引用 + grounding Layer 0 白嫖的前提
        assertThat(TaggedSourceContentInjector.inferId(seg, 99)).isEqualTo("org.md#0");
        assertThat(seg.text()).contains("张三 —隶属于→ 李四");
    }

    @Test
    void retriever_groupsTriplesBySource() {
        TenantContext.set(new TenantContext.Tenant(T1, "u", Set.of()));
        GraphContentRetriever r = new GraphContentRetriever(
                orgGraph(), new TokenEntityLinker(orgGraph(), new SimpleKeywordTokenizer()), 1, 30);
        // 张三 的两条订单边都在 org.md#2 → 合并成一个 Content；隶属于 那条在 org.md#0 → 另一个 Content
        List<Content> contents = r.retrieve(Query.from("张三的情况"));
        assertThat(contents).hasSize(2);
        assertThat(contents).anyMatch(c -> c.textSegment().text().contains("订单#1001")
                && c.textSegment().text().contains("订单#1002"));
    }

    @Test
    void retriever_noSeed_returnsEmpty() {
        TenantContext.set(new TenantContext.Tenant(T1, "u", Set.of()));
        GraphContentRetriever r = new GraphContentRetriever(
                orgGraph(), new TokenEntityLinker(orgGraph(), new SimpleKeywordTokenizer()), 1, 30);
        assertThat(r.retrieve(Query.from("无关问题"))).isEmpty();
    }

    @Test
    void retriever_capsAtMaxTriples() {
        TenantContext.set(new TenantContext.Tenant(T1, "u", Set.of()));
        GraphContentRetriever r = new GraphContentRetriever(
                orgGraph(), new TokenEntityLinker(orgGraph(), new SimpleKeywordTokenizer()), 1, 1);
        long totalStatements = r.retrieve(Query.from("张三的情况")).stream()
                .flatMap(c -> List.of(c.textSegment().text().split("\n")).stream())
                .count();
        assertThat(totalStatements).isEqualTo(1);   // maxTriples=1 截断
    }
}
