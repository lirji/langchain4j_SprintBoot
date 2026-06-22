package com.lrj.langchain4j.ai.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 测 {@link StreamGuard} 的流式后处理判定：PII 告警 + 澄清式提问识别。 */
class StreamGuardTest {

    @Test
    void piiWarning_firesOnEmailPhoneId() {
        assertThat(StreamGuard.piiWarningOrNull("联系我 a@b.com")).contains("email");
        assertThat(StreamGuard.piiWarningOrNull("电话 13800138000")).contains("phone");
        assertThat(StreamGuard.piiWarningOrNull("身份证 11010119900307123X")).contains("id-card");
    }

    @Test
    void piiWarning_nullOnCleanText() {
        assertThat(StreamGuard.piiWarningOrNull("LangChain4j 是一个 Java 的 LLM 框架。")).isNull();
        assertThat(StreamGuard.piiWarningOrNull(null)).isNull();
    }

    @Test
    void clarifyingQuestion_detectedWithCueAndQuestionMark() {
        assertThat(StreamGuard.looksLikeClarifyingQuestion("请问您是指哪个订单？")).isTrue();
        assertThat(StreamGuard.looksLikeClarifyingQuestion("Could you clarify which order you mean?")).isTrue();
    }

    @Test
    void notClarifying_whenNoQuestionMark() {
        assertThat(StreamGuard.looksLikeClarifyingQuestion("LangChain4j 是一个 Java 框架。")).isFalse();
    }

    @Test
    void notClarifying_questionMarkButNoCue() {
        // 以问号结尾但不是澄清话术（普通寒暄）—— 不应误判
        assertThat(StreamGuard.looksLikeClarifyingQuestion("你今天过得怎么样？")).isFalse();
    }

    @Test
    void notClarifying_whenTooLong() {
        String longAnswer = "这是一段很长的回答".repeat(40) + "请问您是指哪个？";
        assertThat(longAnswer.length()).isGreaterThan(240);
        assertThat(StreamGuard.looksLikeClarifyingQuestion(longAnswer)).isFalse();
    }
}
