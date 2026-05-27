package com.lrj.langchain4j.rag.hybrid;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * In-process mirror of ingested segments so a keyword retriever can scan text
 * without round-tripping to the vector store (most stores don't expose text scan).
 * Trades memory for simplicity; for large corpora swap this for Lucene RAMDirectory
 * or a dedicated keyword index (Elasticsearch / Meilisearch).
 */
@Component
public class DocumentMirror {

    private final List<TextSegment> segments = new CopyOnWriteArrayList<>();

    public void add(List<TextSegment> newSegments) {
        segments.addAll(newSegments);
    }

    public List<TextSegment> all() {
        return List.copyOf(segments);
    }

    public int size() {
        return segments.size();
    }

    public void clear() {
        segments.clear();
    }

    /**
     * 按谓词删除片段。配合 {@code DocumentService.delete} 把 EmbeddingStore 和 mirror 一起同步：
     * 只删向量库 mirror 不同步，hybrid keyword retriever 仍会召回到已删文档的字面命中。
     *
     * <p>{@link CopyOnWriteArrayList#removeIf} 实现是 lock-acquire + 全 list rebuild，
     * 单文档删除 O(N) 但 N 在内存镜像规模下可接受；超大语料请换 Lucene。
     */
    public int removeWhere(Predicate<TextSegment> predicate) {
        int before = segments.size();
        segments.removeIf(predicate);
        return before - segments.size();
    }
}
