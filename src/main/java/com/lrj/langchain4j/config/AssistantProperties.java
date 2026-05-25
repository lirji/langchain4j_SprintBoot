package com.lrj.langchain4j.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主 chat AiService（{@link com.lrj.langchain4j.ai.Assistant}）的 system prompt 可调参数。
 *
 * <p>Prompt 模板在 {@code @SystemMessage} 注解里用 {@code {{language}}} {{tone}}} {{citationPolicy}} {{extra}}
 * 占位，本类提供默认值。Controller 把这些值在每次调用时透传给 Assistant；
 * 调用方也可以临时覆盖（例如某个 endpoint 想让回答更详细就传不同的 tone）。
 *
 * <p>切 provider 时（OpenAI / Claude / Gemini / DeepSeek / Ollama）可以为不同模型
 * 配不同的 default style（小模型需要更明确的指令；Claude 适合 XML 标签风格等）。
 * 目前所有 provider 共享同一份默认值，需要分 provider 调时再扩展。
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
            "引用与来源处理（按以下情况分别处理，互斥）：\n" +
            "  1) 如果系统检索到了文档片段并用于回答，必须用 [doc=文件名#片段号] 形式标注来源。\n" +
            "  2) 如果用户的问题明确指向文档/知识库（含『文档』『手册』『文献』『资料里』『根据上述材料』等线索），" +
                  "但没有检索到相关内容，回复『未在文档中找到相关内容』。\n" +
            "  3) 其他情况（用户在本轮提供了信息、闲聊、工具调用、定义性问题等），" +
                  "直接根据用户问题与已有上下文作答 —— 不要加『资料里没有提到 X』之类的免责前言，也不要声明检索状态。";

    /** 临时/灰度指令：默认空，调试或灰度新规则时可以从 yml 临时塞进来不动 Assistant。 */
    private String extra = "";

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }
    public String getCitationPolicy() { return citationPolicy; }
    public void setCitationPolicy(String citationPolicy) { this.citationPolicy = citationPolicy; }
    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }
}
