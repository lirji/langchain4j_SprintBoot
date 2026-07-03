package com.lrj.langchain4j.config;

/**
 * {@code app.rag.grounding.*} 配置。RAG 事实幻觉的事后校验开关。
 * 见 {@link com.lrj.langchain4j.ai.grounding.GroundingService}。
 */
public class GroundingProperties {

    /** 命中 grounding 闸门后的处置策略。 */
    public enum OnFail {
        /** 只在答案末尾追加可信度提示（不改写、不拒答）。v1 行为，默认。 */
        WARN,
        /** 用安全弃答话术替换整个答案（宁可不答，不输出未被资料支撑的内容）。 */
        REFUSE,
        /** 带纠正指令重新生成，最多 {@code maxRegenerations} 次；仍不过阈则降级为 WARN（保住最佳尝试 + 提示）。 */
        REGENERATE
    }

    /** 默认关闭 —— 开启则每条触发了 RAG 的回答多 1 次 temp=0 LLM 调用做 faithfulness 判定。 */
    private boolean enabled = false;

    /** faithfulness 聚合分低于此值就触发闸门。0.7 在"宁可多提示"和"别过度打扰"之间取折中。 */
    private double threshold = 0.7;

    /** 命中闸门后怎么处置。默认 {@code WARN}（与历史行为一致，零改写）。 */
    private OnFail onFail = OnFail.WARN;

    /** {@code REGENERATE} 模式下的最大重生成次数（首次生成不计）。默认 1。 */
    private int maxRegenerations = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public OnFail getOnFail() { return onFail; }
    public void setOnFail(OnFail onFail) { this.onFail = onFail; }
    public int getMaxRegenerations() { return maxRegenerations; }
    public void setMaxRegenerations(int maxRegenerations) { this.maxRegenerations = maxRegenerations; }
}
