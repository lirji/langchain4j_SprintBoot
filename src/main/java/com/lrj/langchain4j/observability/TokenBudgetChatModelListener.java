package com.lrj.langchain4j.observability;

import com.lrj.langchain4j.security.TenantContext;
import com.lrj.langchain4j.security.TokenBudgetTracker;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 调用回填 token usage 到 {@link TokenBudgetTracker}。从 {@link TenantContext} 拿 tenant ——
 * MdcCopyingTaskDecorator 已把 TenantContext 透传到 multi-agent worker 子线程，所以并行 worker
 * 发起的 LLM 调用也能正确归属租户。
 *
 * <p>Listener 由 {@code LlmConfig} 通过构造器注入 {@code List<ChatModelListener>} 灌进每个
 * chat builder —— @Component 自动被收集，无需显式装配。
 *
 * <p>{@code app.token-budget.enabled=false} 时不创建 Bean —— 避免 onResponse 跑空回调浪费。
 */
@Component
@ConditionalOnProperty(name = "app.token-budget.enabled", havingValue = "true", matchIfMissing = true)
public class TokenBudgetChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetChatModelListener.class);

    private final TokenBudgetTracker tracker;

    public TokenBudgetChatModelListener(TokenBudgetTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        TokenUsage tu = ctx.chatResponse().metadata().tokenUsage();
        if (tu == null) return;
        long total = nz(tu.inputTokenCount()) + nz(tu.outputTokenCount());
        if (total <= 0) return;
        String tenantId = TenantContext.current().tenantId();
        tracker.consume(tenantId, total);
        if (log.isDebugEnabled()) {
            log.debug("token-budget commit tenant={} +{} (input={} output={}) totalUsed={}",
                    tenantId, total, tu.inputTokenCount(), tu.outputTokenCount(),
                    tracker.currentUsed(tenantId));
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        // 失败的调用不扣 budget —— 跟"按成功消耗计费"的常见 SaaS 计费习惯一致
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v.longValue();
    }
}
