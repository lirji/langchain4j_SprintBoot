package com.lrj.langchain4j.rag;

import com.lrj.langchain4j.rag.hybrid.DocumentMirror;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentMirror documentMirror;
    private final Path documentsDir;
    private final String chunkingStrategy;
    private final int chunkMaxChars;
    private final int chunkOverlap;

    public RagIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel,
                               DocumentMirror documentMirror,
                               @Value("${app.rag.documents-dir}") String documentsDir,
                               @Value("${app.rag.chunking.strategy:recursive}") String chunkingStrategy,
                               @Value("${app.rag.chunking.max-chars:300}") int chunkMaxChars,
                               @Value("${app.rag.chunking.overlap:50}") int chunkOverlap) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.documentsDir = Paths.get(documentsDir);
        this.chunkingStrategy = chunkingStrategy == null ? "recursive" : chunkingStrategy.trim().toLowerCase();
        this.chunkMaxChars = chunkMaxChars;
        this.chunkOverlap = chunkOverlap;
    }

    public int ingestFromConfiguredDir() {
        return ingestFromConfiguredDir(null);
    }

    public int ingestFromConfiguredDir(String category) {
        if (!Files.isDirectory(documentsDir)) {
            log.warn("RAG documents dir does not exist: {}", documentsDir.toAbsolutePath());
            return 0;
        }
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(documentsDir);
        if (category != null && !category.isBlank()) {
            documents.forEach(d -> d.metadata().put("category", category));
        }
        DocumentSplitter splitter = buildSplitter();
        List<TextSegment> segments = documents.stream()
                .flatMap(d -> splitter.split(d).stream())
                .toList();
        EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(splitter)
                .build()
                .ingest(documents);
        documentMirror.add(segments);
        log.info("Ingested {} documents ({} segments, strategy={} max-chars={} overlap={}) from {} (category={})",
                documents.size(), segments.size(), chunkingStrategy, chunkMaxChars, chunkOverlap,
                documentsDir.toAbsolutePath(), category);
        return documents.size();
    }

    /**
     * 按 {@code app.rag.chunking.strategy} 构造 splitter：
     * <ul>
     *   <li>{@code recursive}（默认）— 字符数硬切 + overlap，简单粗暴；任意文档都能用</li>
     *   <li>{@code markdown-header} — 按 {@code ##} 切 section，超长 fallback 到 recursive。
     *       适合结构化 markdown，chunk = 完整主题</li>
     * </ul>
     */
    private DocumentSplitter buildSplitter() {
        DocumentSplitter recursive = DocumentSplitters.recursive(chunkMaxChars, chunkOverlap);
        return switch (chunkingStrategy) {
            case "recursive" -> recursive;
            case "markdown-header" -> new MarkdownHeaderSplitter(chunkMaxChars, recursive);
            default -> {
                log.warn("Unknown app.rag.chunking.strategy '{}', falling back to recursive", chunkingStrategy);
                yield recursive;
            }
        };
    }
}
