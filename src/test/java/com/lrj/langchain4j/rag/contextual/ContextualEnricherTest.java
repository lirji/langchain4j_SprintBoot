package com.lrj.langchain4j.rag.contextual;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contextual Retrieval 入库改写的确定性行为：前缀拼接 / metadata 透传 / 单 chunk 跳过 /
 * 某块失败保留原文不崩 / 文档截断。用桩 {@link ChunkContextualizer}，不连模型。
 */
class ContextualEnricherTest {

    private static TextSegment seg(String text, String index) {
        Metadata m = new Metadata();
        m.put("file_name", "guide.md");
        m.put("index", index);
        return TextSegment.from(text, m);
    }

    @Test
    void prependsContextAndPreservesMetadata() {
        ChunkContextualizer stub = (doc, chunk) -> "本块出自《指南》的安装章节。";
        ContextualEnricher enricher = new ContextualEnricher(stub, 8000, 2);

        List<TextSegment> out = enricher.enrich("整篇文档内容……",
                List.of(seg("先执行 mvn package。", "0"), seg("再启动应用。", "1")));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo("本块出自《指南》的安装章节。\n\n先执行 mvn package。");
        // metadata 原样保留（index / file_name 不动）
        assertThat(out.get(0).metadata().getString("index")).isEqualTo("0");
        assertThat(out.get(0).metadata().getString("file_name")).isEqualTo("guide.md");
        assertThat(out.get(1).text()).startsWith("本块出自《指南》的安装章节。\n\n再启动");
    }

    @Test
    void skipsSingleChunkDocument() {
        ChunkContextualizer stub = (doc, chunk) -> { throw new AssertionError("不该被调用"); };
        ContextualEnricher enricher = new ContextualEnricher(stub, 8000, 2);

        List<TextSegment> in = List.of(seg("整篇就一个块。", "0"));
        // segments 数 < min-segments(2) → 原样返回，不调用 LLM
        assertThat(enricher.enrich("整篇就一个块。", in)).isSameAs(in);
    }

    @Test
    void chunkFailureKeepsOriginalText() {
        // 第二块抛异常 → 保留原文不前缀，整体不崩
        ChunkContextualizer stub = (doc, chunk) -> {
            if (chunk.contains("炸")) throw new RuntimeException("LLM down");
            return "上下文前缀。";
        };
        ContextualEnricher enricher = new ContextualEnricher(stub, 8000, 2);

        List<TextSegment> out = enricher.enrich("doc",
                List.of(seg("正常块。", "0"), seg("会炸的块。", "1")));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo("上下文前缀。\n\n正常块。");
        assertThat(out.get(1).text()).isEqualTo("会炸的块。");   // 原文保留
    }

    @Test
    void truncatesLongDocumentBeforeFeedingLlm() {
        StringBuilder seen = new StringBuilder();
        ChunkContextualizer stub = (doc, chunk) -> { seen.append("[").append(doc.length()).append("]"); return "ctx"; };
        ContextualEnricher enricher = new ContextualEnricher(stub, 100, 2);

        String longDoc = "x".repeat(5000);
        enricher.enrich(longDoc, List.of(seg("a", "0"), seg("b", "1")));

        // 喂给 LLM 的文档被截到 max-doc-chars=100
        assertThat(seen.toString()).isEqualTo("[100][100]");
    }

    @Test
    void blankDocumentReturnsSegmentsUnchanged() {
        ChunkContextualizer stub = (doc, chunk) -> "ctx";
        ContextualEnricher enricher = new ContextualEnricher(stub, 8000, 2);

        List<TextSegment> in = List.of(seg("a", "0"), seg("b", "1"));
        assertThat(enricher.enrich("  ", in)).isSameAs(in);
    }
}
