package com.lrj.langchain4j.ai.cascade;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 成本级联 ChatModel：包裹「便宜模型 + 强模型」，先便宜后升级。
 *
 * <p>实现 {@link ChatModel}，所以任何吃 {@code ChatModel} 的地方（{@code AiServices.builder} /
 * 直接 {@code chat()}）都能透明用上级联。<strong>被包裹的两个模型用
 * {@code LlmConfig#buildJudgeChatModel} 的方式程序化构建，不注册成第二个 ChatModel Bean</strong>
 * （否则破坏 {@code @AiService} 自动发现）。
 *
 * <p>流程：便宜模型作答 → {@link ConfidenceGate} 判置信 → 够用则返回便宜结果（指标 {@code served=cheap}），
 * 否则强模型重答（指标 {@code served=strong}）。便宜模型触发工具调用时直接返回（无文本可判、
 * 交给上层工具循环），不升级。
 *
 * <p>指标：{@code llm.cascade{served=cheap|strong}} counter，量化省了多少次强模型调用。
 */
public class CascadeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CascadeChatModel.class);

    static final String METRIC = "llm.cascade";

    private final ChatModel cheap;
    private final ChatModel strong;
    private final ConfidenceGate gate;
    private final MeterRegistry registry;

    public CascadeChatModel(ChatModel cheap, ChatModel strong, ConfidenceGate gate, MeterRegistry registry) {
        this.cheap = cheap;
        this.strong = strong;
        this.gate = gate;
        this.registry = registry;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return escalate(request).response();
    }

    /**
     * 级联主逻辑：返回最终 {@link ChatResponse} + 由谁作答 + 便宜模型是否被判置信。
     * 供 {@link CascadeService} 拿到 served 明细，也是 {@link #chat(ChatRequest)} 的底层。
     */
    public Outcome escalate(ChatRequest request) {
        ChatResponse cheapResp = cheap.chat(request);

        // 便宜模型直接要调工具：没有可判的文本，交回上层工具循环，不升级。
        if (cheapResp.aiMessage() != null && cheapResp.aiMessage().hasToolExecutionRequests()) {
            served("cheap");
            return new Outcome(cheapResp, "cheap", true);
        }

        String question = lastUserText(request);
        String cheapText = cheapResp.aiMessage() == null ? null : cheapResp.aiMessage().text();

        if (gate.isConfident(question, cheapText)) {
            served("cheap");
            return new Outcome(cheapResp, "cheap", true);
        }

        log.debug("cascade: cheap answer low-confidence, escalating to strong model");
        ChatResponse strongResp = strong.chat(request);
        served("strong");
        return new Outcome(strongResp, "strong", false);
    }

    private void served(String who) {
        if (registry != null) {
            registry.counter(METRIC, Tags.of("served", who)).increment();
        }
    }

    /** 取请求里最后一条 UserMessage 的文本（自评用）；取不到返回空串。 */
    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um && um.hasSingleText()) {
                return um.singleText();
            }
        }
        return "";
    }

    /**
     * 级联单次结果。
     *
     * @param response       返回给调用方的最终响应
     * @param served         "cheap" | "strong"，谁最终作答
     * @param cheapConfident 便宜模型答案是否被判置信（true 时 served 必为 cheap）
     */
    public record Outcome(ChatResponse response, String served, boolean cheapConfident) {}
}
