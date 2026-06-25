package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parent-child（small-to-big）切分的确定性行为：每个 child 挂 parent_id/parent_text、
 * 多个 child 共享同一 parent、parent 是 child 的上下文超集、metadata 透传、极短文本兜底。
 */
class ParentChildSplitterTest {

    // parent 大窗口（每块 ~200 字符）→ child 小窗口（每块 ~60 字符）：一个 parent 切出多个 child
    private final DocumentSplitter splitter = new ParentChildSplitter(
            DocumentSplitters.recursive(200, 0),
            DocumentSplitters.recursive(60, 0));

    /** 拼一段足够长、能切出多个 parent、每个 parent 又能切出多个 child 的文本。 */
    private static String longText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("这是第 ").append(i).append(" 句用于切分测试的中文内容，描述某个主题的细节信息。\n");
        }
        return sb.toString();
    }

    @Test
    void everyChildCarriesParentMetadata() {
        List<TextSegment> children = splitter.split(Document.from(longText()));

        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(c -> {
            assertThat(c.metadata().getString(ParentChildSplitter.PARENT_ID)).isNotBlank();
            assertThat(c.metadata().getString(ParentChildSplitter.PARENT_TEXT)).isNotBlank();
        });
    }

    @Test
    void parentTextIsSupersetOfChildAndMatchesAParentSegment() {
        Document doc = Document.from(longText());
        Set<String> parentTexts = DocumentSplitters.recursive(200, 0).split(doc).stream()
                .map(TextSegment::text)
                .collect(Collectors.toSet());

        List<TextSegment> children = splitter.split(doc);

        assertThat(children).allSatisfy(c -> {
            String parentText = c.metadata().getString(ParentChildSplitter.PARENT_TEXT);
            // 挂的 parent_text 必须确实是某个 parent 的原文（不是凭空拼的）
            assertThat(parentTexts).contains(parentText);
            // child 是从 parent 切出来的小块 → parent 至少不短于 child
            assertThat(parentText.length()).isGreaterThanOrEqualTo(c.text().length());
        });
    }

    @Test
    void multipleChildrenShareOneParentId() {
        List<TextSegment> children = splitter.split(Document.from(longText()));

        long distinctParents = children.stream()
                .map(c -> c.metadata().getString(ParentChildSplitter.PARENT_ID))
                .distinct().count();

        // 小窗口下每个 parent 至少切出 2 个 child → child 数明显多于 parent 数
        assertThat(children.size()).isGreaterThan((int) distinctParents);
        // parent_id 在文档内 0-based 连续
        Set<String> ids = children.stream()
                .map(c -> c.metadata().getString(ParentChildSplitter.PARENT_ID))
                .collect(Collectors.toSet());
        for (int i = 0; i < distinctParents; i++) {
            assertThat(ids).contains(String.valueOf(i));
        }
    }

    @Test
    void inheritsParentDocumentMetadata() {
        Metadata base = new Metadata();
        base.put("tenantId", "acme");
        base.put("file_name", "guide.md");
        base.put("category", "manual");

        List<TextSegment> children = splitter.split(Document.from(longText(), base));

        assertThat(children).allSatisfy(c -> {
            assertThat(c.metadata().getString("tenantId")).isEqualTo("acme");
            assertThat(c.metadata().getString("file_name")).isEqualTo("guide.md");
            assertThat(c.metadata().getString("category")).isEqualTo("manual");
        });
    }

    @Test
    void shortTextBecomesSingleChildWithSelfAsParent() {
        List<TextSegment> children = splitter.split(Document.from("一句话。"));

        assertThat(children).hasSize(1);
        TextSegment only = children.get(0);
        assertThat(only.metadata().getString(ParentChildSplitter.PARENT_ID)).isEqualTo("0");
        assertThat(only.metadata().getString(ParentChildSplitter.PARENT_TEXT)).contains("一句话");
    }
}
