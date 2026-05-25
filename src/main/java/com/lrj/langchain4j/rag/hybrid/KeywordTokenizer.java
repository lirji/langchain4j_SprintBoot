package com.lrj.langchain4j.rag.hybrid;

import java.util.Set;

/**
 * Splits text into a bag-of-keywords used by {@link KeywordContentRetriever}.
 * Distinct from {@code dev.langchain4j.model.Tokenizer} (which counts LLM tokens) —
 * this one is for keyword retrieval only, so impls can throw away stopwords and case.
 */
public interface KeywordTokenizer {

    Set<String> tokenize(String text);
}
