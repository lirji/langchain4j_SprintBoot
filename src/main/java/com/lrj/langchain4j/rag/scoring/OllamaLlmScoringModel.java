package com.lrj.langchain4j.rag.scoring;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-reranker. Asks the configured {@link ChatModel} to score (query, doc) pairs
 * on a 0..1 scale. Cheap to wire (no extra deps) but slow: O(N) sequential model calls
 * per query. Suitable for local Ollama setups; swap in JinaScoringModel /
 * CohereScoringModel / OnnxScoringModel for production-grade latency.
 */
public class OllamaLlmScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmScoringModel.class);
    private static final Pattern NUMBER = Pattern.compile("(0(?:\\.\\d+)?|1(?:\\.0+)?|\\.\\d+)");
    private static final int DOC_CHAR_BUDGET = 2000;

    private final ChatModel chatModel;

    public OllamaLlmScoringModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<Double> scores = new ArrayList<>(segments.size());
        for (TextSegment seg : segments) {
            scores.add(scoreOne(seg.text(), query));
        }
        return Response.from(scores);
    }

    private double scoreOne(String document, String query) {
        String prompt = """
                Rate the relevance of the DOCUMENT to the QUERY on a scale from 0.0 to 1.0.
                Respond with ONLY a single number between 0 and 1 (no words, no units).

                QUERY:
                %s

                DOCUMENT:
                %s

                Relevance score (0.0 - 1.0):""".formatted(query, truncate(document));
        try {
            String raw = chatModel.chat(prompt);
            return parse(raw);
        } catch (Exception e) {
            log.warn("Reranker scoring failed, defaulting to 0.0", e);
            return 0.0;
        }
    }

    private static double parse(String raw) {
        if (raw == null) return 0.0;
        Matcher m = NUMBER.matcher(raw.trim());
        if (!m.find()) return 0.0;
        try {
            double v = Double.parseDouble(m.group(1));
            return Math.max(0.0, Math.min(1.0, v));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String truncate(String s) {
        return s.length() <= DOC_CHAR_BUDGET ? s : s.substring(0, DOC_CHAR_BUDGET) + "...";
    }
}
