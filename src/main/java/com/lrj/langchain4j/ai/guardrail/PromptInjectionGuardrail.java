package com.lrj.langchain4j.ai.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LangChain4j {@link InputGuardrail} 实现 —— 把 {@link PromptInjectionDetector} 接到
 * {@code @InputGuardrails(PromptInjectionGuardrail.class)} 的 AiService 入口。
 *
 * <p>spring-boot-starter 看见 {@code @InputGuardrails(Foo.class)} 会优先从 spring 容器
 * {@code getBean(Foo.class)} 拿实例（找不到才反射 new）—— 所以 detector 能被注入。
 *
 * <p>action 映射：
 * <ul>
 *   <li>{@code BLOCK} → {@link InputGuardrailResult#fatal}：终止 chain，调用方拿到错误</li>
 *   <li>{@code SANITIZE} → {@code successWith("[QUERY REDACTED ...]")}：模型仍执行但看不到原文</li>
 *   <li>{@code AUDIT} → {@code success()}：只记 warn，请求放行（生产灰度阶段用，观察误伤率）</li>
 * </ul>
 */
@Component
public class PromptInjectionGuardrail implements InputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuardrail.class);

    private final PromptInjectionDetector detector;

    public PromptInjectionGuardrail(PromptInjectionDetector detector) {
        this.detector = detector;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        PromptInjectionDetector.Detection d = detector.detect(text);
        if (!d.injection()) return success();

        log.warn("prompt-injection detected reason={} confidence={} action={}",
                d.reason(), d.confidence(), detector.action());

        return switch (detector.action()) {
            case BLOCK -> fatal("Prompt injection detected (" + d.reason() + ")");
            case SANITIZE -> successWith("[QUERY REDACTED DUE TO SUSPECTED PROMPT INJECTION]");
            case AUDIT -> success();
        };
    }
}
