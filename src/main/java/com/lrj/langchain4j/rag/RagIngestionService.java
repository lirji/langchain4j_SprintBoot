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

    public RagIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel,
                               DocumentMirror documentMirror,
                               @Value("${app.rag.documents-dir}") String documentsDir) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.documentsDir = Paths.get(documentsDir);
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
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 50);
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
        log.info("Ingested {} documents ({} segments) from {} (category={})",
                documents.size(), segments.size(), documentsDir.toAbsolutePath(), category);
        return documents.size();
    }
}
