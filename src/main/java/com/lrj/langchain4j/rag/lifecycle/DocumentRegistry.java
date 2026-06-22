package com.lrj.langchain4j.rag.lifecycle;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tenant 文档级元数据注册表：{@code tenantId -> docId -> DocumentInfo}。
 * EmbeddingStore 存 segment 级数据（向量），这里存"一个文档=多 segment"的逻辑视图，
 * 列表 / 详情 / 删除 / 版本管理从这里读。
 *
 * <p>两个实现按 {@code app.rag.registry.store} 切换：
 * <ul>
 *   <li>{@link InMemoryDocumentRegistry}（默认）— 进程内存，重启即丢。单实例 / 本地开发够用。</li>
 *   <li>{@link RedisDocumentRegistry}（{@code redis}）— 持久化，重启 / 多实例后 {@code GET /rag/documents}
 *       仍列得出。生产知识库（kb profile）用这个，跟持久化向量库（Milvus）配套。</li>
 * </ul>
 *
 * <p>注意：registry 与 EmbeddingStore 是两套存储，需配置上保持一致 —— 持久化向量库就该配持久化
 * registry，否则会出现"向量还在但列表空"（重启后 list 返回 [] 但 RAG 仍答得出）的割裂。
 */
public interface DocumentRegistry {

    void put(DocumentInfo info);

    Optional<DocumentInfo> get(String tenantId, String docId);

    List<DocumentInfo> list(String tenantId);

    Optional<DocumentInfo> remove(String tenantId, String docId);

    /** 仅给 actuator / debug 用 —— 返回全部租户的浅快照。 */
    Map<String, Collection<DocumentInfo>> snapshotAll();
}
