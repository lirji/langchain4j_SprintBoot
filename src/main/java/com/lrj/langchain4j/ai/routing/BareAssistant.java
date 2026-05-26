package com.lrj.langchain4j.ai.routing;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.guardrail.PiiGuardrail;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.guardrail.OutputGuardrails;

/**
 * 跟 {@link Assistant} 同型号，但**不挂 RetrievalAugmentor** —— 走 TOOL/CHAT 路径时用，
 * 跳过 RAG embedding + vector search 的开销。
 *
 * <p>不用 {@code @AiService} 注解 —— 让 LangChain4j Spring auto-discover 跳过它；
 * 由 {@link com.lrj.langchain4j.config.QueryRoutingConfig} 用 {@code AiServices.builder()}
 * 程序化构建，显式只挂 ChatModel + ChatMemoryProvider + Tools，不挂 augmentor。
 *
 * <p>跟 Assistant 共用同一份 SYSTEM_PROMPT（citationPolicy 的规则 3 覆盖"无检索"场景，
 * 不会瞎吐 [doc=...] 引用），同一 chatMemoryProvider，所以会话连续性保留 —— 同一 chatId
 * 在 Assistant 和 BareAssistant 之间切换不会丢历史。
 */
public interface BareAssistant {

    @SystemMessage(Assistant.SYSTEM_PROMPT)
    @OutputGuardrails(value = PiiGuardrail.class, maxRetries = 2)
    String chat(@MemoryId String chatId,
                @V("language") String language,
                @V("tone") String tone,
                @V("citationPolicy") String citationPolicy,
                @V("extra") String extra,
                @UserMessage String userMessage);
}
