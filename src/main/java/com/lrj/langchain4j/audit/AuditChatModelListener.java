package com.lrj.langchain4j.audit;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每次 LLM 调用落一条审计 —— provider / model / tokens / latency / 错误。
 * 跟 {@code MetricsChatModelListener} 数据源相同但用途不同：metrics 是聚合 dashboard、
 * audit 是个体可追溯（比如某个客户为某次请求扣了多少 token、调用了哪个 model）。
 *
 * <p>多线程：multi-agent worker 子线程发起的 LLM 调用，{@code TenantContext} 和 MDC 已被
 * {@code MdcCopyingTaskDecorator} 透传，所以 audit 能正确归属租户和 trace。
 */
@Component
public class AuditChatModelListener implements ChatModelListener {

    private static final String START_KEY = "lrj.audit.startNanos";

    private final AuditLogger audit;

    public AuditChatModelListener(AuditLogger audit) {
        this.audit = audit;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(START_KEY, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        ChatResponseMetadata md = ctx.chatResponse().metadata();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("provider", String.valueOf(ctx.modelProvider()));
        fields.put("model", md.modelName());
        fields.put("latencyMs", elapsedMs(ctx.attributes().get(START_KEY)));
        TokenUsage tu = md.tokenUsage();
        if (tu != null) {
            fields.put("inputTokens", tu.inputTokenCount());
            fields.put("outputTokens", tu.outputTokenCount());
            fields.put("totalTokens", tu.totalTokenCount());
        }
        audit.record(AuditEventType.LLM_REQUEST, fields);
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("provider", String.valueOf(ctx.modelProvider()));
        fields.put("model", ctx.chatRequest().parameters().modelName());
        fields.put("latencyMs", elapsedMs(ctx.attributes().get(START_KEY)));
        fields.put("error", ctx.error().getClass().getSimpleName());
        fields.put("errorMessage", ctx.error().getMessage());
        audit.record(AuditEventType.LLM_ERROR, fields);
    }

    private static long elapsedMs(Object startNanos) {
        if (!(startNanos instanceof Long start)) return -1;
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
