package com.lrj.langchain4j.rag.hybrid;

import com.lrj.langchain4j.rag.CategoryContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keyword retriever: token-overlap score (|q ∩ d| / |q|) over the corpus mirror,
 * with tokenization delegated to a {@link KeywordTokenizer}. RRF in the aggregator
 * only cares about rank, so this lightweight scoring is fine for fusion.
 */
public class KeywordContentRetriever implements ContentRetriever {

    private final DocumentMirror mirror;
    private final KeywordTokenizer tokenizer;
    private final int maxResults;

    public KeywordContentRetriever(DocumentMirror mirror, KeywordTokenizer tokenizer, int maxResults) {
        this.mirror = mirror;
        this.tokenizer = tokenizer;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String category = CategoryContext.get();
        Set<String> qTokens = tokenizer.tokenize(query.text());
        if (qTokens.isEmpty()) return List.of();

        return mirror.all().stream()
                .filter(s -> categoryMatches(s, category))
                .map(s -> new Scored(s, score(qTokens, s.text())))
                .filter(sc -> sc.score > 0)
                .sorted(Comparator.<Scored>comparingDouble(sc -> sc.score).reversed())
                .limit(maxResults)
                .map(sc -> Content.from(sc.segment))
                .collect(Collectors.toList());
    }

    private static boolean categoryMatches(TextSegment s, String category) {
        if (category == null) return true;
        return s.metadata() != null && Objects.equals(category, s.metadata().getString("category"));
    }

    private double score(Set<String> queryTokens, String docText) {
        Set<String> docTokens = tokenizer.tokenize(docText);
        if (docTokens.isEmpty()) return 0.0;
        int overlap = 0;
        for (String t : queryTokens) {
            if (docTokens.contains(t)) overlap++;
        }
        // weight by query coverage so very-long docs don't get an unfair edge
        return (double) overlap / queryTokens.size();
    }

    private record Scored(TextSegment segment, double score) {}
}
