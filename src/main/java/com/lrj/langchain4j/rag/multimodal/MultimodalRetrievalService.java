package com.lrj.langchain4j.rag.multimodal;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 原生多模态检索：把图片直接 embed 进跨模态向量空间存进现有 {@link EmbeddingStore}，并支持用
 * 「文本 query」检索这些图片（text→image）。区别于 {@code ai/vision} 的 caption→text 路径。
 *
 * <p><strong>多租户隔离</strong>：入库时给 image 片段打 {@code tenantId} metadata，检索时强制 AND
 * 一个 {@code tenantId} filter（复用 {@link TenantContext}，与主 RAG 链 {@code LangChain4jConfig}
 * 的 {@code tenantScopedFilter} 同套路）。
 *
 * <p><strong>维度安全（重要）</strong>：image 向量来自 CLIP/jina-clip，维度与主 RAG 的文本 embedding
 * 通常不同。检索时强制 AND {@code type=image} filter，让 {@link EmbeddingStore} 只在「同为 image
 * 的向量」间算相似度——既保证语义相关（只找图），也避免把 CLIP 维度的 query 拿去和文本 chunk 向量
 * 做点积而维度不符报错。持久化向量库（pgvector/milvus）建议给 image 单开一个维度匹配的集合，见
 * {@code docs/multimodal-embedding.md}。
 */
public class MultimodalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalRetrievalService.class);

    /** 标识一条记录是「原生 image 向量」，检索/隔离两用。 */
    public static final String TYPE_IMAGE = "image";

    private final MultimodalEmbeddingModel model;
    private final EmbeddingStore<TextSegment> store;
    private final int defaultTopK;
    private final double defaultMinScore;

    public MultimodalRetrievalService(MultimodalEmbeddingModel model,
                                      EmbeddingStore<TextSegment> store,
                                      int defaultTopK,
                                      double defaultMinScore) {
        this.model = model;
        this.store = store;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
    }

    /**
     * 入库钩子：把一张图片 embed 成向量存进 {@link EmbeddingStore}，带 {@code type=image} /
     * {@code file_name} / {@code tenantId} / {@code mime} metadata。
     *
     * @param image    图片字节
     * @param mimeType MIME
     * @param fileName 来源文件名（进 metadata，检索结果按它回指）
     * @return store 生成的记录 id
     */
    public String ingestImage(byte[] image, String mimeType, String fileName) {
        float[] vector = model.embedImage(image, mimeType);
        String tenantId = TenantContext.current().tenantId();
        String name = (fileName == null || fileName.isBlank()) ? "image" : fileName;

        Metadata metadata = new Metadata();
        metadata.put("type", TYPE_IMAGE);
        metadata.put("file_name", name);
        metadata.put("tenantId", tenantId);
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.put("mime", mimeType);
        }
        // image 片段没有天然文本内容；用文件名占位（TextSegment 需要非空文本，且检索结果里方便展示）。
        TextSegment segment = TextSegment.from("[image] " + name, metadata);

        String id = store.add(Embedding.from(vector), segment);
        log.info("ingested image: file={} tenant={} dim={} id={}", name, tenantId, vector.length, id);
        return id;
    }

    /**
     * text→image 检索：把文本 query 用同一个多模态模型 embed，在 image 向量里找最近邻。
     *
     * @param query      查询文本
     * @param maxResults 返回条数（&le;0 用默认）
     * @param minScore   最小相似度（&lt;0 用默认）
     * @return 命中的图片（file_name + score），按相似度降序
     */
    public List<ImageMatch> searchByText(String query, int maxResults, double minScore) {
        int topK = maxResults > 0 ? maxResults : defaultTopK;
        double floor = minScore >= 0 ? minScore : defaultMinScore;
        float[] queryVector = model.embedText(query);

        String tenantId = TenantContext.current().tenantId();
        Filter filter = Filter.and(
                metadataKey("tenantId").isEqualTo(tenantId),
                metadataKey("type").isEqualTo(TYPE_IMAGE));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(topK)
                .minScore(floor)
                .filter(filter)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        List<ImageMatch> out = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> m : matches) {
            String fileName = m.embedded() != null && m.embedded().metadata() != null
                    ? m.embedded().metadata().getString("file_name") : null;
            out.add(new ImageMatch(m.embeddingId(), fileName, m.score()));
        }
        log.info("image-search: query='{}' tenant={} topK={} minScore={} -> {} hits",
                query, tenantId, topK, floor, out.size());
        return out;
    }

    /** 一条 text→image 命中：记录 id / 文件名 / 相似度。 */
    public record ImageMatch(String id, String fileName, double score) {}
}
