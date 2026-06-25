package com.lrj.langchain4j.rag;

import com.lrj.langchain4j.observability.ChunkMetrics;
import com.lrj.langchain4j.rag.contextual.ContextualEnricher;
import com.lrj.langchain4j.rag.graph.GraphIngestor;
import com.lrj.langchain4j.rag.hybrid.DocumentMirror;
import com.lrj.langchain4j.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
    private final DocumentSplitterFactory splitterFactory;
    // GraphRAG 软依赖：app.rag.graph.enabled=false 时 Bean 不存在 → getIfAvailable() 返 null，零开销
    private final ObjectProvider<GraphIngestor> graphIngestorProvider;
    // Contextual Retrieval 软依赖：app.rag.contextual.enabled=false 时 Bean 不存在 → null，零开销
    private final ObjectProvider<ContextualEnricher> contextualEnricherProvider;
    private final ChunkMetrics chunkMetrics;
    private final Path documentsDir;

    public RagIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel,
                               DocumentMirror documentMirror,
                               DocumentSplitterFactory splitterFactory,
                               ObjectProvider<GraphIngestor> graphIngestorProvider,
                               ObjectProvider<ContextualEnricher> contextualEnricherProvider,
                               ChunkMetrics chunkMetrics,
                               @Value("${app.rag.documents-dir}") String documentsDir) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.splitterFactory = splitterFactory;
        this.graphIngestorProvider = graphIngestorProvider;
        this.contextualEnricherProvider = contextualEnricherProvider;
        this.chunkMetrics = chunkMetrics;
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
        // tenantId 是隔离 RAG 的核心 metadata；retriever 的 dynamicFilter 会强制 AND 上去
        String tenantId = TenantContext.current().tenantId();
        documents.forEach(d -> d.metadata().put("tenantId", tenantId));
        if (category != null && !category.isBlank()) {
            documents.forEach(d -> d.metadata().put("category", category));
        }
        // 切一次 → 直接 embed + add（不走 EmbeddingStoreIngestor，后者会在内部对 documents 再 split 一遍：
        // 对 recursive 等纯文本切分无所谓，但对 semantic 这种「切分阶段就要逐句 embed」的策略等于双倍 embedding
        // 成本。与 DocumentService.upload 的单上传路径口径一致）。
        // Contextual Retrieval：开启时按文档分组改写（每个 chunk 加文档级上下文前缀再 embed）。
        // 必须 per-document 处理（上下文生成需要整篇原文），故不能在 flatMap 后统一改。
        DocumentSplitter splitter = splitterFactory.create();
        ContextualEnricher enricher = contextualEnricherProvider.getIfAvailable();
        List<TextSegment> segments = documents.stream()
                .flatMap(d -> {
                    List<TextSegment> segs = splitter.split(d);
                    return (enricher != null ? enricher.enrich(d.text(), segs) : segs).stream();
                })
                .toList();
        chunkMetrics.record(splitterFactory.strategy(), documents.size(), segments);
        if (!segments.isEmpty()) {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
        }
        documentMirror.add(segments);
        // GraphRAG：开启时同步抽三元组建图（一次性、可能多次 LLM 调用，仅 graph.enabled 时跑）
        GraphIngestor graphIngestor = graphIngestorProvider.getIfAvailable();
        if (graphIngestor != null) {
            graphIngestor.ingest(segments);
        }
        log.info("Ingested {} documents ({} segments, strategy={} unit={} max-size={} overlap={}) from {} (tenant={} category={})",
                documents.size(), segments.size(), splitterFactory.strategy(), splitterFactory.unit(),
                splitterFactory.maxSize(), splitterFactory.overlap(),
                documentsDir.toAbsolutePath(), tenantId, category);
        return documents.size();
    }
}
