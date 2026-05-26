package com.lrj.langchain4j.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 主 chat AiService（{@link com.lrj.langchain4j.ai.Assistant}）的 system prompt 可调参数。
 *
 * <p>Prompt 模板在 {@code @SystemMessage} 注解里用 {@code {{language}}} {{tone}}}
 * {@code {{citationPolicy}} {{extra}}} 占位，本类提供**默认值**；
 * {@link #overrides} 按 provider 部分覆盖（DeepSeek 重中文短指令、Claude 偏 XML 标签、
 * Gemini 工具触发不积极要更诱导等）。
 *
 * <p>调用方不直接用 {@code AssistantProperties}，而是用 {@link ResolvedAssistantStyle}
 * （启动时由 {@link AssistantStyleConfig} 按当前 {@code app.llm.provider} 解析好）—— 解耦
 * "配置长什么样" 和 "运行时实际用哪份"。
 */
@Component
@ConfigurationProperties(prefix = "app.assistant")
public class AssistantProperties {

    /** 回答使用的语言（"中文" / "English" / "日本語" 等）。 */
    private String language = "中文";

    /** 语气与详尽度，模型直接照念。 */
    private String tone = "简洁，1–2 句话答完，必要时再展开";

    /**
     * 引用策略。
     *
     * <p>注意作用域：只在「问题确实指向文档/知识库」时才约束引用与"没有检索到"的话术；
     * 用户直接提供信息（如让你整理一段联系方式）、闲聊、工具驱动的问答（如问当前时间），
     * 都不该加"资料里没有提到"之类的免责前言 —— 之前 eval 暴露过这个回归。
     */
    private String citationPolicy =
            "引用与来源处理（按以下情况分别处理，互斥）：\n"
            + "  1) 如果系统检索到了文档片段并用于回答，必须用 [doc=文件名#片段号] 形式标注来源。\n"
            + "  2) 如果用户的问题明确指向文档/知识库（含『文档』『手册』『文献』『资料里』"
            + "『根据上述材料』等线索），但没有检索到相关内容，回复『未在文档中找到相关内容』。\n"
            + "  3) 其他情况（用户在本轮提供了信息、闲聊、工具调用、定义性问题等），"
            + "直接根据用户问题与已有上下文作答 —— 不要加『资料里没有提到 X』之类的免责前言，"
            + "也不要声明检索状态。";

    /** 临时/灰度指令：默认空，调试或灰度新规则时可以从 yml 临时塞进来不动 Assistant。 */
    private String extra = "";

    /**
     * 按 provider 的部分覆盖。key 是 {@code app.llm.provider} 的值
     * （{@code ollama|openai|anthropic|gemini|deepseek|vllm}）；
     * value 里任何字段为 null 就 fallback 到上面的默认值。
     */
    private Map<String, Override> overrides = new HashMap<>();

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }
    public String getCitationPolicy() { return citationPolicy; }
    public void setCitationPolicy(String citationPolicy) { this.citationPolicy = citationPolicy; }
    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }
    public Map<String, Override> getOverrides() { return overrides; }
    public void setOverrides(Map<String, Override> overrides) { this.overrides = overrides; }

    /**
     * 按 provider 解析出实际生效的 style。{@code provider} 不在 overrides 里时返回纯默认。
     * {@link AssistantStyleConfig} 在启动时调用一次，注入 {@link ResolvedAssistantStyle} Bean。
     */
    public ResolvedAssistantStyle resolve(String provider) {
        Override ov = overrides == null ? null : overrides.get(provider);
        if (ov == null) {
            return new ResolvedAssistantStyle(language, tone, citationPolicy, extra);
        }
        return new ResolvedAssistantStyle(
                ov.getLanguage() != null ? ov.getLanguage() : language,
                ov.getTone() != null ? ov.getTone() : tone,
                ov.getCitationPolicy() != null ? ov.getCitationPolicy() : citationPolicy,
                ov.getExtra() != null ? ov.getExtra() : extra);
    }

    /**
     * Provider 部分覆盖。所有字段允许 null —— null 表示沿用默认，不是清空。
     * 想真的清空某个字段（比如不要 citationPolicy）传空串。
     */
    public static class Override {
        private String language;
        private String tone;
        private String citationPolicy;
        private String extra;

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getTone() { return tone; }
        public void setTone(String tone) { this.tone = tone; }
        public String getCitationPolicy() { return citationPolicy; }
        public void setCitationPolicy(String citationPolicy) { this.citationPolicy = citationPolicy; }
        public String getExtra() { return extra; }
        public void setExtra(String extra) { this.extra = extra; }
    }
}
