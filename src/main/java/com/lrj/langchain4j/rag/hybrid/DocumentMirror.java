package com.lrj.langchain4j.rag.hybrid;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
}
