package com.lrj.langchain4j.cost;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每次 LLM 调用后把 token 用量翻成 USD，累计到 {@link CostTracker}（per-tenant 日）并打进 Micrometer
 * （{@code gen_ai.client.cost.usd} counter，按 model/provider tag）。
 *
 * <p>与 {@link com.lrj.langchain4j.observability.TokenBudgetChatModelListener} 并列：那个按 token
 * <em>拦截</em>（配额），这个按 USD <em>观测</em>（成本）。从 {@link TenantContext} 拿 tenant ——
 * MdcCopyingTaskDecorator 已把 TenantContext 透传到 multi-agent worker 子线程，成本归属正确。
 *
 * <p>Micrometer counter <strong>不带 tenant tag</strong>（避免 Prometheus label 基数爆炸）；
 * per-tenant 明细走 {@link CostTracker} 的内存快照 + {@code /actuator/cost} 端点。
 *
 * <p>{@code app.cost.enabled=false} 时整个 {@code cost} 包不装配，本 listener 不存在、零回调开销。
 */
public class CostChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(CostChatModelListener.class);

    private final CostCalculator calculator;
    private final CostTracker tracker;
    private final MeterRegistry registry;

    public CostChatModelListener(CostCalculator calculator, CostTracker tracker, MeterRegistry registry) {
        this.calculator = calculator;
        this.tracker = tracker;
        this.registry = registry;
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        ChatResponseMetadata md = ctx.chatResponse().metadata();
        TokenUsage tu = md.tokenUsage();
        if (tu == null) return;

        long input = nz(tu.inputTokenCount());
        long output = nz(tu.outputTokenCount());
        long cacheRead = 0, cacheWrite = 0;
        if (tu instanceof AnthropicTokenUsage atu) {
            cacheRead = nz(atu.cacheReadInputTokens());
            cacheWrite = nz(atu.cacheCreationInputTokens());
        }

        String model = safe(md.modelName());
        CostCalculator.Cost cost = calculator.compute(model,
                new CostCalculator.Tokens(input, output, cacheRead, cacheWrite));
        if (cost.totalUsd() <= 0) return; // 免费模型（default 价全 0）不打点、不累计

        String tenantId = TenantContext.current().tenantId();
        tracker.record(tenantId, cost.totalUsd());

        String provider = safe(ctx.modelProvider().toString());
        registry.counter("gen_ai.client.cost.usd", Tags.of("model", model, "provider", provider))
                .increment(cost.totalUsd());

        if (log.isDebugEnabled()) {
            log.debug("cost tenant={} model={} +${} (in={} out={} cr={} cw={}) todayTotal=${}",
                    tenantId, model, cost.totalUsd(), input, output, cacheRead, cacheWrite,
                    tracker.currentUsd(tenantId));
        }
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
