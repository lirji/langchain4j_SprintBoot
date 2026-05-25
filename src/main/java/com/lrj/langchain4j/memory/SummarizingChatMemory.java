package com.lrj.langchain4j.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ChatMemory that, on every {@link #add}, compacts old messages into a single
 * {@link SystemMessage} summary when the message count exceeds {@code threshold}.
 * The most recent {@code keepRecent} messages are kept verbatim so the model
 * always sees fresh context.
 * <p>
 * Tradeoffs vs MessageWindow / TokenWindow:
 * <ul>
 *   <li>+ keeps long-term facts that a pure sliding window would drop</li>
 *   <li>- every compaction is an extra LLM call (latency + cost)</li>
 *   <li>- summary quality depends on the model; can drop nuance</li>
 * </ul>
 */
public class SummarizingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemory.class);
    private static final String SUMMARY_PREFIX = "[Conversation summary so far]\n";

    private final Object id;
    private final ChatMemoryStore store;
    private final ChatModel summarizer;
    private final int threshold;
    private final int keepRecent;

    public SummarizingChatMemory(Object id, ChatMemoryStore store, ChatModel summarizer,
                                 int threshold, int keepRecent) {
        if (keepRecent >= threshold) {
            throw new IllegalArgumentException("keepRecent must be < threshold");
        }
        this.id = id;
        this.store = store;
        this.summarizer = summarizer;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> current = new ArrayList<>(store.getMessages(id));
        current.add(message);
        if (current.size() > threshold) {
            current = compact(current);
        }
        store.updateMessages(id, current);
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    private List<ChatMessage> compact(List<ChatMessage> messages) {
        int compactCount = messages.size() - keepRecent;
        List<ChatMessage> toCompact = messages.subList(0, compactCount);
        List<ChatMessage> recent = new ArrayList<>(messages.subList(compactCount, messages.size()));

        String existingSummary = extractExistingSummary(toCompact);
        String transcript = renderTranscript(stripSummary(toCompact));
        String prompt = """
                Concisely summarize the following conversation so a future reader can pick up
                without losing important facts, decisions, user preferences, or open questions.
                Output 5-10 short bullet points. No preamble.

                %sNEW MESSAGES:
                %s
                """.formatted(existingSummary.isEmpty() ? "" : "EXISTING SUMMARY:\n" + existingSummary + "\n\n",
                        transcript);
        String summary;
        try {
            summary = summarizer.chat(prompt).trim();
        } catch (Exception e) {
            log.warn("Memory summarization failed; falling back to truncation", e);
            return new ArrayList<>(recent);
        }
        log.debug("Compacted {} messages into summary ({} chars)", compactCount, summary.length());

        List<ChatMessage> result = new ArrayList<>(recent.size() + 1);
        result.add(SystemMessage.from(SUMMARY_PREFIX + summary));
        result.addAll(recent);
        return result;
    }

    private static String extractExistingSummary(List<ChatMessage> messages) {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys && sys.text().startsWith(SUMMARY_PREFIX)) {
            return sys.text().substring(SUMMARY_PREFIX.length());
        }
        return "";
    }

    private static List<ChatMessage> stripSummary(List<ChatMessage> messages) {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys && sys.text().startsWith(SUMMARY_PREFIX)) {
            return messages.subList(1, messages.size());
        }
        return messages;
    }

    private static String renderTranscript(List<ChatMessage> messages) {
        return messages.stream()
                .map(SummarizingChatMemory::render)
                .collect(Collectors.joining("\n"));
    }

    private static String render(ChatMessage m) {
        if (m instanceof UserMessage u) return "USER: " + u.singleText();
        if (m instanceof AiMessage a) return "ASSISTANT: " + a.text();
        if (m instanceof SystemMessage s) return "SYSTEM: " + s.text();
        return m.getClass().getSimpleName() + ": " + m;
    }
}
