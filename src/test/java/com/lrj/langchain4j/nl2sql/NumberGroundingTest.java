package com.lrj.langchain4j.nl2sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 测 {@link NumberGrounding} 的确定性数字核对：受支撑来源 / 豁免类 / 归一。 */
class NumberGroundingTest {

    @Test
    void numberInRows_isSupported() {
        var r = List.<Map<String, Object>>of(Map.of("name", "赵六", "total", 5400));
        assertThat(NumberGrounding.unsupportedNumbers("退款最高的是赵六，共 5400 元。", r, "退款最高的客户？", 1))
                .isEmpty();
    }

    @Test
    void hallucinatedNumber_isFlagged() {
        var r = List.<Map<String, Object>>of(Map.of("total", 5400));
        assertThat(NumberGrounding.unsupportedNumbers("一共退款 9999 元。", r, "退款多少？", 1))
                .containsExactly("9999");
    }

    @Test
    void thousandsSeparatorAndTrailingZeros_normalizeAndMatch() {
        // SQL 返回 5400.00，答案写 5,400 —— 归一后都是 5400，应视为有据
        var r = List.<Map<String, Object>>of(Map.of("total", "5400.00"));
        assertThat(NumberGrounding.unsupportedNumbers("合计 5,400 元。", r, "q", 1)).isEmpty();
    }

    @Test
    void smallIntegersExempt_notFlagged() {
        var r = List.<Map<String, Object>>of(Map.of("total", 5400));
        // "前 3 名" 的 3 是序数，≤10 豁免，不当幻觉
        assertThat(NumberGrounding.unsupportedNumbers("前 3 名客户合计 5400。", r, "q", 1)).isEmpty();
    }

    @Test
    void yearExempt_notFlagged() {
        var r = List.<Map<String, Object>>of(Map.of("total", 5400));
        assertThat(NumberGrounding.unsupportedNumbers("2026 年合计 5400 元。", r, "q", 1)).isEmpty();
    }

    @Test
    void questionNumber_isSupported() {
        var r = List.<Map<String, Object>>of(Map.of("amount", 8800));
        // 7777 来自用户问题（订单号），不算幻觉
        assertThat(NumberGrounding.unsupportedNumbers("订单 7777 已退款 8800 元。", r, "订单 ORD-7777 退款多少？", 1))
                .isEmpty();
    }

    @Test
    void rowCount_isSupported() {
        var r = List.<Map<String, Object>>of(Map.of("amount", 100), Map.of("amount", 200));
        // 答案说"共 15 笔"，rowCount=15 → 有据（且 >10 不被豁免，真靠 rowCount 支撑）
        assertThat(NumberGrounding.unsupportedNumbers("共 15 笔，金额 100 与 200。", r, "q", 15)).isEmpty();
        // 若说"共 16 笔"而 rowCount=15 → 16 无据被标
        assertThat(NumberGrounding.unsupportedNumbers("共 16 笔。", r, "q", 15)).containsExactly("16");
    }

    @Test
    void normalize_stripsCommasAndTrailingZeros() {
        assertThat(NumberGrounding.normalize("5,400.00")).isEqualTo("5400");
        assertThat(NumberGrounding.normalize("0.50")).isEqualTo("0.5");
        assertThat(NumberGrounding.normalize("1870.0")).isEqualTo("1870");
    }
}
