package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 markdown-header 策略的边界：每 section 1 chunk、超长 fallback、空文档、非 markdown 文档。
 */
class MarkdownHeaderSplitterTest {

    private final DocumentSplitter splitter = new MarkdownHeaderSplitter(
            600, DocumentSplitters.recursive(300, 50));

    @Test
    void simpleMarkdown_oneSegmentPerSection() {
        String md = """
                # Title

                ## Section A

                Section A content.

                ## Section B

                Section B content.
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        // 第一个是 # Title 之前 + 整段开头（不带 ##），单独成 1 个；A 和 B 各 1 个 = 共 3
        // 注意 # 开头不匹配 (?=^##+ )，所以 # Title 跟它下面的内容直到 ## Section A 之前是一个 section
        assertThat(segs).hasSize(3);
        assertThat(segs.get(1).text()).startsWith("## Section A");
        assertThat(segs.get(1).metadata().getString("section")).isEqualTo("Section A");
        assertThat(segs.get(2).metadata().getString("section")).isEqualTo("Section B");
    }

    @Test
    void indexMetadataIsSequential() {
        String md = """
                ## A
                a
                ## B
                b
                ## C
                c
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).metadata().getString("index")).isEqualTo("0");
        assertThat(segs.get(1).metadata().getString("index")).isEqualTo("1");
        assertThat(segs.get(2).metadata().getString("index")).isEqualTo("2");
    }

    @Test
    void longSection_fallsBackToRecursive_producingMultipleSegments() {
        // 一个超过 600 char 的 section，应该被 fallback splitter 切成多块
        String longBody = "a".repeat(2000);
        String md = "## Big\n\n" + longBody;
        List<TextSegment> segs = splitter.split(Document.from(md));
        // recursive(300, 50) 切 2000+ chars 应该 >1 段
        assertThat(segs.size()).isGreaterThan(1);
        // 都应该带 section 标题元信息
        assertThat(segs.get(0).metadata().getString("section")).isEqualTo("Big");
    }

    @Test
    void nonMarkdown_singleSegment() {
        // 没有 ## 行的纯文本，整篇成 1 个 segment（短的）
        String text = "just a short paragraph without any markdown headers.";
        List<TextSegment> segs = splitter.split(Document.from(text));
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).text()).contains("short paragraph");
        // 没 heading 所以 section meta 是整段第一行（strip 后即原文本，因为没 # 前缀）
        // 这是 fallback 行为，不强求测什么
    }

    @Test
    void deepHeadings_alsoSplit() {
        // ### / #### 也算 splitting boundary（regex 是 ##+）
        String md = """
                ## Top
                top body
                ### Sub
                sub body
                #### SubSub
                subsub body
                """;
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(3);
    }

    @Test
    void tokenUnit_thresholdMeasuredInTokens_notChars() {
        // 同一段英文：char 数 >> token 数（1 token ≈ 4 char）。
        // 阈值 30，section 约 120 char / ~25 token。
        //   - char 计量：120 > 30 → 触发 fallback 切多段
        //   - token 计量：~25 < 30 → 不切，整段 1 个 segment
        // 用这个差异证明 token estimator 路径真的生效（按 token 数比阈值，不是字符数）。
        String body = "The quick brown fox jumps over the lazy dog again and again to fill the section body.";
        String md = "## S\n\n" + body;
        TokenCountEstimator estimator = new OpenAiTokenCountEstimator("gpt-4o-mini");

        DocumentSplitter charMode = new MarkdownHeaderSplitter(30, DocumentSplitters.recursive(20, 5));
        DocumentSplitter tokenMode = new MarkdownHeaderSplitter(
                30, DocumentSplitters.recursive(20, 5, estimator), estimator);

        assertThat(charMode.split(Document.from(md))).hasSizeGreaterThan(1);   // 按字符超阈 → fallback
        assertThat(tokenMode.split(Document.from(md))).hasSize(1);             // 按 token 不超 → 整段
    }

    @Test
    void emptySections_skipped() {
        // ## A 后面紧跟 ## B，A 是空 section（只有 heading）—— 仍 emit 不跳
        // 但 leading 空白（## 出现前的空内容）被 strip 后跳过
        String md = "\n\n## A\n## B\n";
        List<TextSegment> segs = splitter.split(Document.from(md));
        assertThat(segs).hasSize(2);
    }
}
