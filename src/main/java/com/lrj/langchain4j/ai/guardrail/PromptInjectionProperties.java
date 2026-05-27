package com.lrj.langchain4j.ai.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.guardrail.injection.*} 配置。
 *
 * <pre>
 * app.guardrail.injection:
 *   enabled: true                    # 全局开关；关掉等价于 guardrail 直接 success()
 *   action: block                    # block | sanitize | audit
 *   llm:
 *     enabled: false                 # 是否额外跑 LLM 分类器（每条 query 多 1 次 LLM call）
 *     confidence-threshold: 0.7      # LLM 返回 confidence > 此值 才判定 injection
 * </pre>
 *
 * action 语义：
 * <ul>
 *   <li>{@code block}（默认）— 拦截，{@code fatal} 终止调用并返回错误。最安全</li>
 *   <li>{@code sanitize} — 把 user message 替换成 {@code [QUERY REDACTED]}，模型仍执行但拿不到原文。
 *       适合"宁可丢请求也别误伤"的场景</li>
 *   <li>{@code audit} — 只 warn 日志，放行原 query。生产灰度阶段先用这个观察误伤率</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.guardrail.injection")
public class PromptInjectionProperties {

    private boolean enabled = true;
    private Action action = Action.BLOCK;
    private Llm llm = new Llm();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public enum Action { BLOCK, SANITIZE, AUDIT }

    public static class Llm {
        private boolean enabled = false;
        private double confidenceThreshold = 0.7;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    }
}
