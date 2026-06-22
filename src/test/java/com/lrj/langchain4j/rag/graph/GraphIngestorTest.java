package com.lrj.langchain4j.rag.graph;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphIngestor 确定性逻辑单测（用 stub extractor，不连模型）：别名规范化 / 受限 schema 过滤 / async 投递。
 */
class GraphIngestorTest {

    private static final Executor DIRECT = Runnable::run;

    private static TextSegment seg(String text) {
        return TextSegment.from(text, Metadata.from(Map.of(
                "tenantId", "t1", "file_name", "doc.md", "index", "0")));
    }

    /** 固定返回给定裸三元组的抽取器。 */
    private static GraphExtractor stub(RawTriple... triples) {
        return (text, hint) -> new ExtractedTriples(List.of(triples));
    }

    @Test
    void aliasCanonicalization_mergesSurfaceForms() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphIngestor ing = new GraphIngestor(
                stub(new RawTriple("张三经理", "隶属于", "李四")), store, 12,
                DIRECT, false,
                Set.of(),
                Map.of("张三经理", "张三"));     // 别名表

        ing.ingest(List.of(seg("...")));
        // "张三经理" 入库时被规范化成 "张三" → 用 "张三" 能遍历到这条边
        assertThat(store.neighbors(Set.of("张三"), 1, "t1", null))
                .extracting(Triple::subject).containsExactly("张三");
    }

    @Test
    void relationWhitelist_dropsOffSchemaEdges() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphIngestor ing = new GraphIngestor(
                stub(new RawTriple("张三", "隶属于", "李四"),
                     new RawTriple("张三", "喜欢", "咖啡")), store, 12,
                DIRECT, false,
                Set.of("隶属于"),                // 只允许「隶属于」
                Map.of());

        int n = ing.ingest(List.of(seg("...")));
        assertThat(n).isEqualTo(1);             // 「喜欢」被 schema 过滤掉
        assertThat(store.neighbors(Set.of("张三"), 1, "t1", null))
                .extracting(Triple::relation).containsExactly("隶属于");
    }

    @Test
    void relationWhitelist_empty_keepsAll() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphIngestor ing = new GraphIngestor(
                stub(new RawTriple("张三", "隶属于", "李四"),
                     new RawTriple("张三", "喜欢", "咖啡")), store, 12,
                DIRECT, false, Set.of(), Map.of());
        assertThat(ing.ingest(List.of(seg("...")))).isEqualTo(2);
    }

    @Test
    void async_submitsToExecutor_doesNotBuildInline() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        Deque<Runnable> queue = new ArrayDeque<>();
        Executor deferred = queue::add;        // 不立即执行，攒着
        GraphIngestor ing = new GraphIngestor(
                stub(new RawTriple("张三", "隶属于", "李四")), store, 12,
                deferred, true,                 // async=true
                Set.of(), Map.of());

        int ret = ing.ingest(List.of(seg("...")));
        assertThat(ret).isEqualTo(-1);          // async：投递即返回
        assertThat(store.size()).isZero();      // 还没建
        queue.poll().run();                     // 后台跑
        assertThat(store.size()).isEqualTo(1);  // 建好了
    }

    @Test
    void blankTriples_skipped() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphIngestor ing = new GraphIngestor(
                stub(new RawTriple("张三", "隶属于", ""),
                     new RawTriple("", "负责", "华东大区")), store, 12,
                DIRECT, false, Set.of(), Map.of());
        assertThat(ing.ingest(List.of(seg("...")))).isZero();
    }
}
