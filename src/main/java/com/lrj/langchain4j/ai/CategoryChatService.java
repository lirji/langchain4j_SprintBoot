package com.lrj.langchain4j.ai;

import com.lrj.langchain4j.config.AssistantProperties;
import com.lrj.langchain4j.rag.CategoryContext;
import org.springframework.stereotype.Service;

/**
 * Demonstrates per-call dynamic metadata filtering for RAG.
 * Sets the category in {@link CategoryContext} so the shared {@code ContentRetriever}
 * (configured with {@code dynamicFilter}) scopes its similarity search to documents
 * whose metadata.category matches. The {@code finally} clear keeps the ThreadLocal
 * clean for the next request on the same worker thread.
 */
@Service
public class CategoryChatService {

    private final Assistant assistant;
    private final AssistantProperties props;

    public CategoryChatService(Assistant assistant, AssistantProperties props) {
        this.assistant = assistant;
        this.props = props;
    }

    public String chatInCategory(String chatId, String category, String userMessage) {
        try {
            CategoryContext.set(category);
            return assistant.chat(chatId,
                    props.getLanguage(),
                    props.getTone(),
                    props.getCitationPolicy(),
                    props.getExtra(),
                    userMessage);
        } finally {
            CategoryContext.clear();
        }
    }
}
