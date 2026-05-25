package com.lrj.langchain4j.rag;

/**
 * Per-request holder for the current RAG category filter.
 * The default {@code ContentRetriever} consults this via a dynamicFilter,
 * so any call that sets a category before invoking the AiService will scope
 * retrieval to documents whose metadata.category matches.
 */
public final class CategoryContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CategoryContext() {}

    public static void set(String category) {
        CURRENT.set(category);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
