package com.lrj.langchain4j.ai;

import com.lrj.langchain4j.ai.guardrail.PiiGuardrail;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * 主 chat AiService。
 *
 * <p>{@code @SystemMessage} 拆成 5 段（# Role / # Language &amp; Style / # Tool Use / # Citation /
 * # Safety）+ 1 段灰度位（# Extra）。`{{language}}` `{{tone}}` `{{citationPolicy}}` `{{extra}}`
 * 由 {@link com.lrj.langchain4j.config.AssistantProperties} 提供默认值，
 * {@code ChatController} 在每次调用时透传 —— 想灰度 / A/B 试 prompt 不用改 Java 代码，
 * 只改 yml 重启即可。
 */
@AiService
public interface Assistant {

    String SYSTEM_PROMPT = """
            # Role
            You are a focused, factual assistant embedded in a Java/Spring backend.
            Default to answering the question; only ask a clarifying question when the
            request is genuinely ambiguous.

            # Language & Style
            Reply in: {{language}}
            Tone: {{tone}}

            # Tool Use
            - If a registered tool can authoritatively answer (current time, dates,
              file lookups, etc.), call it instead of guessing.
            - Never fabricate tool parameters. If a required parameter is missing,
              ask the user for it in one sentence.
            - Don't announce that you're about to call a tool — just call it.

            # Citation
            {{citationPolicy}}

            # Safety
            Never include personal contact details (email addresses, phone numbers,
            ID / passport numbers, bank cards) in your responses. Redact any such
            value as [REDACTED] even when the user provided it.

            # Extra
            {{extra}}
            """;

    @SystemMessage(SYSTEM_PROMPT)
    @OutputGuardrails(value = PiiGuardrail.class, maxRetries = 2)
    String chat(@MemoryId String chatId,
                @V("language") String language,
                @V("tone") String tone,
                @V("citationPolicy") String citationPolicy,
                @V("extra") String extra,
                @UserMessage String userMessage);

    @SystemMessage(SYSTEM_PROMPT)
    TokenStream chatStream(@MemoryId String chatId,
                           @V("language") String language,
                           @V("tone") String tone,
                           @V("citationPolicy") String citationPolicy,
                           @V("extra") String extra,
                           @UserMessage String userMessage);
}
