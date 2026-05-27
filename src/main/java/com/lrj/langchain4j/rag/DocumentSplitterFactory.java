package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring 单例：按 {@code app.rag.chunking.*} 配置生产 {@link DocumentSplitter}。
 *
 * <p>抽出来让 {@link RagIngestionService}（批量从 documents/ 目录入库）和
 * {@code DocumentService}（单文档 upload）共享同一份 chunking 策略，不必各 build 一遍。
 *
 * <p>支持策略：
 * <ul>
 *   <li>{@code recursive}（默认）— {@code DocumentSplitters.recursive(maxChars, overlap)}</li>
 *   <li>{@code markdown-header} — {@link MarkdownHeaderSplitter}：按 ## 切 section，超长 fallback recursive</li>
 * </ul>
 */
@Component
public class DocumentSplitterFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentSplitterFactory.class);

    private final String strategy;
    private final int maxChars;
    private final int overlap;

    public DocumentSplitterFactory(
            @Value("${app.rag.chunking.strategy:recursive}") String strategy,
            @Value("${app.rag.chunking.max-chars:300}") int maxChars,
            @Value("${app.rag.chunking.overlap:50}") int overlap) {
        this.strategy = strategy == null ? "recursive" : strategy.trim().toLowerCase();
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    public DocumentSplitter create() {
        DocumentSplitter recursive = DocumentSplitters.recursive(maxChars, overlap);
        return switch (strategy) {
            case "recursive" -> recursive;
            case "markdown-header" -> new MarkdownHeaderSplitter(maxChars, recursive);
            default -> {
                log.warn("Unknown app.rag.chunking.strategy '{}', falling back to recursive", strategy);
                yield recursive;
            }
        };
    }

    public String strategy() { return strategy; }
    public int maxChars() { return maxChars; }
    public int overlap() { return overlap; }
}
