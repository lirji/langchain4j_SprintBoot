package com.lrj.langchain4j.ai.cascade;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Model Cascade 服务入口：把一句自然语言问题跑一遍级联，返回答案 + 由谁作答。
 *
 * <p>薄封装 {@link CascadeChatModel#escalate}，把 served 明细透传给 {@link CascadeController}。
 * 不带 ChatMemory / RAG —— 级联本身是「模型选择」层，与检索 / 记忆正交（要叠加可把
 * {@link CascadeChatModel} 当普通 ChatModel 喂给 {@code AiServices.builder}）。
 */
public class CascadeService {

    private final CascadeChatModel cascade;

    public CascadeService(CascadeChatModel cascade) {
        this.cascade = cascade;
    }

    public Result ask(String question) {
        String q = question == null ? "" : question;
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(q))
                .build();
        CascadeChatModel.Outcome outcome = cascade.escalate(request);
        ChatResponse resp = outcome.response();
        String answer = (resp.aiMessage() == null) ? "" : resp.aiMessage().text();
        return new Result(q, answer, outcome.served(), outcome.cheapConfident());
    }

    /**
     * 级联结果 DTO（直接序列化返回给 {@code POST /chat/cascade}）。
     *
     * @param question       原问题
     * @param answer         最终答案
     * @param served         "cheap" | "strong"，谁作答（成本可见）
     * @param cheapConfident 便宜模型是否被判置信（false = 发生了升级）
     */
    public record Result(String question, String answer, String served, boolean cheapConfident) {}
}
