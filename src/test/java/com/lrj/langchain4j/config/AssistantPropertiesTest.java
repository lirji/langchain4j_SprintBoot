package com.lrj.langchain4j.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 per-provider override 部分合并逻辑（round f）。
 * 关键不变量：null/缺失 = fallback 到默认；空串 = 真清空（语义区分）。
 */
class AssistantPropertiesTest {

    @Test
    void resolve_noOverride_returnsDefaults() {
        var p = new AssistantProperties();
        var s = p.resolve("openai");
        assertThat(s.language()).isEqualTo("中文");
        assertThat(s.tone()).contains("简洁");
        assertThat(s.citationPolicy()).contains("引用与来源");
        assertThat(s.extra()).isEmpty();
    }

    @Test
    void resolve_unknownProvider_returnsDefaults() {
        var p = new AssistantProperties();
        var s = p.resolve("nonexistent-provider");
        assertThat(s.language()).isEqualTo("中文");
    }

    @Test
    void resolve_partialOverride_unspecifiedFieldsFallback() {
        var p = new AssistantProperties();
        var ov = new AssistantProperties.Override();
        ov.setTone("custom claude tone");
        // language / citationPolicy / extra 不 set → null → 应该 fallback
        p.getOverrides().put("anthropic", ov);

        var s = p.resolve("anthropic");
        assertThat(s.tone()).isEqualTo("custom claude tone");
        assertThat(s.language()).isEqualTo("中文");
        assertThat(s.citationPolicy()).contains("引用与来源");
        assertThat(s.extra()).isEmpty();
    }

    @Test
    void resolve_emptyStringOverride_meansActuallyEmpty_notFallback() {
        // 关键语义区分：空串 ≠ null。空串 = 真不要这个字段
        var p = new AssistantProperties();
        var ov = new AssistantProperties.Override();
        ov.setExtra("");
        p.getOverrides().put("openai", ov);

        var s = p.resolve("openai");
        assertThat(s.extra()).isEmpty();   // 真清空
        // 其他字段仍 fallback
        assertThat(s.language()).isEqualTo("中文");
    }

    @Test
    void resolve_nullOverridesMap_doesNotCrash() {
        var p = new AssistantProperties();
        p.setOverrides(null);
        var s = p.resolve("openai");
        assertThat(s.language()).isEqualTo("中文");
    }

    @Test
    void resolve_fullOverride_replacesAllFields() {
        var p = new AssistantProperties();
        var ov = new AssistantProperties.Override();
        ov.setLanguage("English");
        ov.setTone("concise");
        ov.setCitationPolicy("cite as [n]");
        ov.setExtra("be terse");
        p.getOverrides().put("vllm", ov);

        var s = p.resolve("vllm");
        assertThat(s.language()).isEqualTo("English");
        assertThat(s.tone()).isEqualTo("concise");
        assertThat(s.citationPolicy()).isEqualTo("cite as [n]");
        assertThat(s.extra()).isEqualTo("be terse");
    }
}
