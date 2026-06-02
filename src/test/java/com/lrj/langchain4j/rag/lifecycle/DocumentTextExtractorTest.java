package com.lrj.langchain4j.rag.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 离线确定性单测：Tika 对 text/plain 和 markdown 字节流的解析是稳定的，不连网不连模型。
 * PDF/Office 的真解析放到端到端验证（需 fixture + 体积），不进单测。
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsPlainText() {
        String text = extractor.extract(bytes("knowledge base answer 42"), "note.txt");
        assertThat(text).contains("knowledge base answer 42");
    }

    @Test
    void extractsMarkdownBody() {
        String md = """
                # 部署指南
                先起 Milvus，再用 kb profile 启动。
                """;
        String text = extractor.extract(bytes(md), "guide.md");
        assertThat(text).contains("部署指南").contains("Milvus");
    }

    @Test
    void blankInputRejected() {
        assertThatThrownBy(() -> extractor.extract(bytes("   \n  "), "empty.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty.txt");
    }
}
