package com.lrj.langchain4j.rag.lifecycle;

import java.time.Instant;

/**
 * 文档级元数据，per-tenant 在 {@link DocumentRegistry} 里维护。EmbeddingStore 里存的是 segment
 * 级数据（向量 + 片段文本 + metadata），这里维护的是"一个文档=多个 segment"的逻辑视图，方便
 * 列表 / 删除 / 版本管理。
 *
 * @param docId        SHA-256(tenantId + ":" + displayName) 前 16 hex，URL-safe，同名重传保持稳定
 * @param tenantId     租户隔离 key
 * @param displayName  上传时的文件名 / title
 * @param contentType  MIME（上传时透传，仅回显；正文由 Apache Tika 解析，支持 PDF/Office/text 等）
 * @param sizeBytes    原始字节数（统计用）
 * @param segmentCount 切片数量
 * @param version      第几次上传同名文档，从 1 开始
 * @param uploadedAt   最近一次上传时间
 * @param category     可选分类标签，对应现有 {@code app.rag.category} 检索过滤
 */
public record DocumentInfo(
        String docId,
        String tenantId,
        String displayName,
        String contentType,
        long sizeBytes,
        int segmentCount,
        int version,
        Instant uploadedAt,
        String category) {
}
