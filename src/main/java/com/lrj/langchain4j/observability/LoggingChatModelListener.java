package com.lrj.langchain4j.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs every chat-model call with model, duration, finish reason and token usage.
 * MDC carries the request traceId set by the web filter, so each line is
 * correlatable across multi-agent fan-out.
 */
public class LoggingChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModelListener.class);
    private static final String START_KEY = "lrj.startNanos";

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(START_KEY, System.nanoTime());
        log.info("llm-request model={} messages={}",
                ctx.chatRequest().parameters().modelName(),
                ctx.chatRequest().messages().size());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        long durMs = elapsedMs(ctx.attributes().get(START_KEY));
        ChatResponse resp = ctx.chatResponse();
        ChatResponseMetadata md = resp.metadata();
        TokenUsage tu = md.tokenUsage();
        log.info("llm-response model={} finish={} duration_ms={} tokens_in={} tokens_out={} tokens_total={}",
                md.modelName(),
                md.finishReason(),
                durMs,
                tu == null ? null : tu.inputTokenCount(),
                tu == null ? null : tu.outputTokenCount(),
                tu == null ? null : tu.totalTokenCount());
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        long durMs = elapsedMs(ctx.attributes().get(START_KEY));
        log.error("llm-error duration_ms={} error={}", durMs, ctx.error().toString());
    }

    private static long elapsedMs(Object startNanos) {
        if (!(startNanos instanceof Long start)) return -1;
        return (System.nanoTime() - start) / 1_000_000;
    }
}
