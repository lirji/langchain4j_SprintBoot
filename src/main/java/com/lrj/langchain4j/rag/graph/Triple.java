package com.lrj.langchain4j.rag.graph;

/**
 * 一条带来源/租户的知识图谱三元组（{@code subject —relation→ object}）。
 *
 * <p>{@code sourceId} 是它被抽取自的 chunk 的稳定 id（{@code file_name#chunkIndex}，
 * 跟 {@link com.lrj.langchain4j.rag.TaggedSourceContentInjector#inferId} 同口径）——
 * 这是 GraphRAG 召回结果能溯源 + 让 grounding Layer 0 引用核对生效的命脉，抽取时丢了它
 * 等于答案不可引用。{@code tenantId}/{@code category} 用于检索时的隔离过滤，跟向量/keyword 两路对称。
 */
public record Triple(String subject, String relation, String object,
                     String sourceId, String tenantId, String category) {
}
