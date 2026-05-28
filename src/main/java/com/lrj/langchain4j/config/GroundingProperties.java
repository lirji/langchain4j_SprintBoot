package com.lrj.langchain4j.config;

/**
 * {@code app.rag.grounding.*} 配置。RAG 事实幻觉的事后校验开关。
 * 见 {@link com.lrj.langchain4j.ai.grounding.GroundingService}。
 */
public class GroundingProperties {

    /** 默认关闭 —— 开启则每条触发了 RAG 的回答多 1 次 temp=0 LLM 调用做 faithfulness 判定。 */
    private boolean enabled = false;

    /** faithfulness 聚合分低于此值就 warn。0.7 在"宁可多提示"和"别过度打扰"之间取折中。 */
    private double threshold = 0.7;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
}
