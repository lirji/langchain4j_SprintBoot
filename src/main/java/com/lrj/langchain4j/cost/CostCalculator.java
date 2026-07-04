package com.lrj.langchain4j.cost;

/**
 * 纯函数：token 用量 → USD。无状态、可确定性单测（{@code CostCalculatorTest}）——
 * 项目一贯做法，凡是"确定性的算术"都进 JUnit，不进 eval。
 *
 * <p>关键细节是 <strong>Anthropic prompt caching 的输入拆分</strong>：{@code AnthropicTokenUsage}
 * 里 {@code inputTokenCount} <em>已包含</em> cache-read + cache-write 的 token，但三者计费单价不同
 * （cache-read 约 0.1×、cache-write 约 1.25×、普通 input 1×）。所以要把普通 input 单独拆出来：
 * {@code regularInput = input − cacheRead − cacheWrite}（夹到 ≥0，防脏数据算出负成本）。
 * 非 Anthropic 场景 cacheRead/cacheWrite 传 0，退化成 input×inputRate + output×outputRate。
 */
public class CostCalculator {

    private static final double PER_MILLION = 1_000_000.0;

    private final CostProperties props;

    public CostCalculator(CostProperties props) {
        this.props = props;
    }

    /** 一次 LLM 调用的 token 拆分。cacheRead/cacheWrite 已含在 input 内（Anthropic 语义）；非 Anthropic 传 0。 */
    public record Tokens(long input, long output, long cacheRead, long cacheWrite) {
        public static Tokens of(long input, long output) {
            return new Tokens(input, output, 0, 0);
        }
    }

    /** USD 成本明细。totalUsd = 四项之和。 */
    public record Cost(double inputUsd, double outputUsd, double cacheReadUsd, double cacheWriteUsd, double totalUsd) {
        public static final Cost ZERO = new Cost(0, 0, 0, 0, 0);
    }

    /** 按 model 单价把 token 拆分乘成 USD。model 不认识时走 default 价（可能全 0）。 */
    public Cost compute(String model, Tokens t) {
        if (t == null) return Cost.ZERO;
        CostProperties.Rate rate = props.resolveRate(model);

        long cacheRead = Math.max(0, t.cacheRead());
        long cacheWrite = Math.max(0, t.cacheWrite());
        // 普通 input = 总 input 减掉两类缓存 token（Anthropic 语义），夹到 ≥0 防脏数据算负。
        long regularInput = Math.max(0, t.input() - cacheRead - cacheWrite);
        long output = Math.max(0, t.output());

        double inputUsd = regularInput / PER_MILLION * rate.getInput();
        double outputUsd = output / PER_MILLION * rate.getOutput();
        double cacheReadUsd = cacheRead / PER_MILLION * rate.effectiveCacheRead();
        double cacheWriteUsd = cacheWrite / PER_MILLION * rate.effectiveCacheWrite();
        double total = inputUsd + outputUsd + cacheReadUsd + cacheWriteUsd;
        return new Cost(inputUsd, outputUsd, cacheReadUsd, cacheWriteUsd, total);
    }
}
