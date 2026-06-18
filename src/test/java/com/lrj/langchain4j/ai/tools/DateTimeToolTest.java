package com.lrj.langchain4j.ai.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 测 {@link DateTimeTool} 的「错误返回可纠错文本而非抛异常」约定。 */
class DateTimeToolTest {

    private final DateTimeTool tool = new DateTimeTool();

    @Test
    void currentDateTime_validZone_returnsTime() {
        String r = tool.currentDateTime("Asia/Shanghai");
        assertThat(r).contains("Asia/Shanghai").doesNotContain("Invalid");
    }

    @Test
    void currentDateTime_badZone_returnsCorrectableText_noThrow() {
        // 非 IANA id —— 旧实现会抛 DateTimeException 中断 chat 回合；现返回可纠错文本
        String r = tool.currentDateTime("北京时间");
        assertThat(r).contains("Invalid zoneId").contains("Asia/Shanghai");
    }

    @Test
    void daysUntil_validDate_returnsNumber() {
        String r = tool.daysUntil("2099-12-31");
        assertThat(r).doesNotContain("Invalid");
        assertThat(Long.parseLong(r.trim())).isGreaterThan(0);
    }

    @Test
    void daysUntil_badDate_returnsCorrectableText_noThrow() {
        String r = tool.daysUntil("next friday");
        assertThat(r).contains("Invalid isoDate").contains("yyyy-MM-dd");
    }
}
