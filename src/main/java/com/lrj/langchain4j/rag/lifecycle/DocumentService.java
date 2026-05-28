package com.lrj.langchain4j.rag.lifecycle;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.rag.DocumentSplitterFactory;
import com.lrj.langchain4j.rag.hybrid.DocumentMirror;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Per-tenant 文档 CRUD。围绕一个核心原则：同一个 (tenantId, displayName) 在向量库里只保留一份，
 * 重复 upload 删旧版本→入新版本，避免检索时召回同一文档的多个历史片段。
 *
 * <p>三个存储层一起同步更新：
 * <ol>
 *   <li>{@link EmbeddingStore}：segment 向量 + metadata（{@code tenantId, docId, displayName, version, category}）</li>
 *   <li>{@link DocumentMirror}：hybrid keyword 检索的内存镜像（用 docId 谓词同步删除）</li>
 *   <li>{@link DocumentRegistry}：文档级元数据，列表/详情 API 从这里读</li>
 * </ol>
 *
 * <p>失败语义：upload 是"先删旧再入新"两步操作，没用事务。如果新版本入库失败，老版本已经被删
 * → 该文档暂时检索不到。生产可以加 try/catch 回滚或者改成"先入新（版本号 +1）再删旧"两阶段。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentMirror documentMirror;
    private final DocumentSplitterFactory splitterFactory;
    private final DocumentRegistry registry;
    private final AuditLogger audit;

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.splitterFactory = splitterFactory;
        this.registry = registry;
        this.audit = audit;
    }

    /**
     * 上传单文档。如果 (tenantId, displayName) 已存在，旧版本先全删（embedding + mirror + registry），
     * 再入库新版本，version 累加。
     *
     * @param displayName 用户给的文件名 / title；docId 由它和 tenantId 派生
     * @param contentType MIME（{@code text/plain} / {@code text/markdown}），主要用于回显
     * @param text        文档全文
     * @param category    可选分类，写进 segment metadata 配合 {@code CategoryContext} 检索过滤
     */
    public DocumentInfo upload(String displayName, String contentType, String text, String category) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text is empty");
        }
        String tenantId = TenantContext.current().tenantId();
        String docId = computeDocId(tenantId, displayName);

        int nextVersion = registry.get(tenantId, docId)
                .map(prev -> {
                    deleteInternal(prev);
                    return prev.version() + 1;
                })
                .orElse(1);

        Document doc = Document.from(text);
        doc.metadata()
                .put("tenantId", tenantId)
                .put("docId", docId)
                .put("displayName", displayName)
                .put("version", String.valueOf(nextVersion));
        if (category != null && !category.isBlank()) {
            doc.metadata().put("category", category);
        }

        DocumentSplitter splitter = splitterFactory.create();
        List<TextSegment> segments = splitter.split(doc);

        // 自己 embed + add，不走 EmbeddingStoreIngestor —— 后者要传 Document 列表会重复 split
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        documentMirror.add(segments);

        DocumentInfo info = new DocumentInfo(
                docId, tenantId, displayName,
                contentType == null ? "text/plain" : contentType,
                text.getBytes(StandardCharsets.UTF_8).length,
                segments.size(), nextVersion, Instant.now(),
                (category == null || category.isBlank()) ? null : category);
        registry.put(info);
        log.info("uploaded doc tenant={} docId={} name='{}' version={} segments={}",
                tenantId, docId, displayName, nextVersion, segments.size());
        audit.record(AuditEventType.DOCUMENT_UPLOADED, Map.of(
                "docId", docId,
                "displayName", displayName,
                "version", nextVersion,
                "segments", segments.size(),
                "sizeBytes", info.sizeBytes(),
                "category", category == null ? "" : category));
        return info;
    }

    public List<DocumentInfo> list() {
        return registry.list(TenantContext.current().tenantId());
    }

    public Optional<DocumentInfo> get(String docId) {
        return registry.get(TenantContext.current().tenantId(), docId);
    }

    /** 删除：先按 tenant 校验 docId 归属，再三层一起删。 */
    public boolean delete(String docId) {
        String tenantId = TenantContext.current().tenantId();
        Optional<DocumentInfo> info = registry.get(tenantId, docId);
        if (info.isEmpty()) return false;
        deleteInternal(info.get());
        registry.remove(tenantId, docId);
        log.info("deleted doc tenant={} docId={} name='{}'", tenantId, docId, info.get().displayName());
        audit.record(AuditEventType.DOCUMENT_DELETED, Map.of(
                "docId", docId,
                "displayName", info.get().displayName(),
                "version", info.get().version()));
        return true;
    }

    /**
     * 删除 embedding + mirror。registry 留给调用方决定何时移除（upload 不移除，会被覆盖；delete 显式移除）。
     *
     * <p>{@code removeAll(Filter)} 是 EmbeddingStore 默认方法 —— InMemory / PGVector / Milvus 支持，
     * 自定义 {@code DorisEmbeddingStore} 需要校验实现，未实现的 store 会抛 UnsupportedOperationException。
     */
    private void deleteInternal(DocumentInfo info) {
        try {
            Filter f = Filter.and(
                    metadataKey("tenantId").isEqualTo(info.tenantId()),
                    metadataKey("docId").isEqualTo(info.docId()));
            embeddingStore.removeAll(f);
        } catch (UnsupportedOperationException ex) {
            log.warn("EmbeddingStore does not support removeAll(Filter); skipping vector delete for docId={}",
                    info.docId(), ex);
        }
        int removed = documentMirror.removeWhere(seg ->
                seg.metadata() != null
                        && Objects.equals(info.tenantId(), seg.metadata().getString("tenantId"))
                        && Objects.equals(info.docId(), seg.metadata().getString("docId")));
        log.debug("removed {} mirror segments for docId={}", removed, info.docId());
    }

    /** SHA-256(tenant + ":" + name) → 前 16 hex；URL-safe 且对 displayName 中的特殊字符鲁棒。 */
    static String computeDocId(String tenantId, String displayName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((tenantId + ":" + displayName).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
