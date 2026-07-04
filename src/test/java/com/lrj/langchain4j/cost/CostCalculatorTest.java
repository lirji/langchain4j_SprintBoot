package com.lrj.langchain4j.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 测 {@link CostCalculator} 的确定性 token→USD 换算：单价解析（精确/前缀/default）、
 * input/output 分档、Anthropic cache 拆分、脏数据夹取。
 */
class CostCalculatorTest {

    private static final double EPS = 1e-9;

    private CostCalculator calc(java.util.function.Consumer<CostProperties> cfg) {
        CostProperties p = new CostProperties();
        cfg.accept(p);
        return new CostCalculator(p);
    }

    @Test
    void inputOutput_pricedPerMillion() {
        CostCalculator c = calc(p -> {
            var r = new CostProperties.Rate();
            r.setInput(0.15);   // $0.15 / 1M
            r.setOutput(0.60);  // $0.60 / 1M
            p.getPricing().put("gpt-4o-mini", r);
        });
        // 1,000,000 input → $0.15 ; 500,000 output → $0.30
        CostCalculator.Cost cost = c.compute("gpt-4o-mini", CostCalculator.Tokens.of(1_000_000, 500_000));
        assertThat(cost.inputUsd()).isCloseTo(0.15, offset(EPS));
        assertThat(cost.outputUsd()).isCloseTo(0.30, offset(EPS));
        assertThat(cost.totalUsd()).isCloseTo(0.45, offset(EPS));
    }

    @Test
    void unknownModel_fallsBackToDefaultRate() {
        CostCalculator c = calc(p -> {
            p.getDefault().setInput(0.0);
            p.getDefault().setOutput(0.0);
        });
        // 本地 ollama 之类：default 全 0 → 成本 0
        CostCalculator.Cost cost = c.compute("llama3.1", CostCalculator.Tokens.of(10_000, 5_000));
        assertThat(cost.totalUsd()).isEqualTo(0.0);
    }

    @Test
    void modelName_longestPrefixMatch() {
        CostCalculator c = calc(p -> {
            var r = new CostProperties.Rate();
            r.setInput(0.15);
            r.setOutput(0.60);
            p.getPricing().put("gpt-4o-mini", r);
        });
        // 配 "gpt-4o-mini" 应命中带版本号的实际返回名
        CostCalculator.Cost cost = c.compute("gpt-4o-mini-2024-07-18", CostCalculator.Tokens.of(1_000_000, 0));
        assertThat(cost.inputUsd()).isCloseTo(0.15, offset(EPS));
    }

    @Test
    void anthropicCache_splitsInputAcrossRates() {
        CostCalculator c = calc(p -> {
            var r = new CostProperties.Rate();
            r.setInput(1.00);       // $1 / 1M 普通 input
            r.setOutput(5.00);
            r.setCacheRead(0.10);   // 命中缓存约 0.1x
            r.setCacheWrite(1.25);  // 建缓存约 1.25x
            p.getPricing().put("claude-haiku-4-5", r);
        });
        // input=1,000,000 里含 cacheRead=600,000 + cacheWrite=200,000 → regular=200,000
        // regular: 0.2 * 1.00 = 0.20 ; cacheRead: 0.6 * 0.10 = 0.06 ; cacheWrite: 0.2 * 1.25 = 0.25
        CostCalculator.Cost cost = c.compute("claude-haiku-4-5",
                new CostCalculator.Tokens(1_000_000, 0, 600_000, 200_000));
        assertThat(cost.inputUsd()).isCloseTo(0.20, offset(EPS));
        assertThat(cost.cacheReadUsd()).isCloseTo(0.06, offset(EPS));
        assertThat(cost.cacheWriteUsd()).isCloseTo(0.25, offset(EPS));
        assertThat(cost.totalUsd()).isCloseTo(0.51, offset(EPS));
    }

    @Test
    void cacheRatesUnset_fallBackToInputRate() {
        CostCalculator c = calc(p -> {
            var r = new CostProperties.Rate();
            r.setInput(2.00);
            r.setOutput(0.0);
            // 不设 cache-read/write → effective 回退 input 价 2.00
            p.getPricing().put("m", r);
        });
        CostCalculator.Cost cost = c.compute("m", new CostCalculator.Tokens(1_000_000, 0, 500_000, 0));
        // regular=500k*2=1.00 ; cacheRead=500k*2=1.00（回退 input 价）
        assertThat(cost.cacheReadUsd()).isCloseTo(1.00, offset(EPS));
        assertThat(cost.inputUsd()).isCloseTo(1.00, offset(EPS));
    }

    @Test
    void dirtyCacheExceedingInput_clampedNonNegative() {
        CostCalculator c = calc(p -> {
            var r = new CostProperties.Rate();
            r.setInput(1.00);
            r.setCacheRead(0.10);
            p.getPricing().put("m", r);
        });
        // cacheRead(2M) > input(1M) → regularInput 夹到 0，不出现负成本
        CostCalculator.Cost cost = c.compute("m", new CostCalculator.Tokens(1_000_000, 0, 2_000_000, 0));
        assertThat(cost.inputUsd()).isEqualTo(0.0);
        assertThat(cost.totalUsd()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void nullTokens_returnsZero() {
        CostCalculator c = calc(p -> {});
        assertThat(c.compute("any", null)).isEqualTo(CostCalculator.Cost.ZERO);
    }
}
