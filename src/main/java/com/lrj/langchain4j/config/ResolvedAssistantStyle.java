package com.lrj.langchain4j.config;

/**
 * 启动时按当前 {@code app.llm.provider} 解析后的实际 style，注入到所有需要传 @V 参数的调用方
 * （{@code ChatController} / {@code CategoryChatService} / {@code EvaluationRunner} /
 * {@code QueryRouterService}）。
 *
 * <p>是 immutable record —— 启动后不变。换 provider 要重启（项目里 provider 本身也是启动期定的）。
 */
public record ResolvedAssistantStyle(
        String language,
        String tone,
        String citationPolicy,
        String extra
) {
    public String getLanguage() { return language; }
    public String getTone() { return tone; }
    public String getCitationPolicy() { return citationPolicy; }
    public String getExtra() { return extra; }
}
