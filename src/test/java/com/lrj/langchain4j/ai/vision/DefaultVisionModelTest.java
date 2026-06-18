package com.lrj.langchain4j.ai.vision;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DefaultVisionModel} 的 caption 缓存（B）确定性单测：用计数 stub ChatModel 验证
 * 同图复用、关闭缓存时不复用、answer 路径永不缓存。
 */
class DefaultVisionModelTest {

    /** 计数 stub：记录底层 LLM 实际被调用了几次，返回固定文本。 */
    static class CountingChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        @Override public ChatResponse chat(ChatRequest chatRequest) {
            calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("desc")).build();
        }
    }

    @Test
    void sameImage_cached_callsModelOnce() {
        CountingChatModel model = new CountingChatModel();
        DefaultVisionModel vm = new DefaultVisionModel(model, "describe", 1_000_000L, 16);
        byte[] img = {1, 2, 3, 4};

        vm.caption(img, "image/png");
        vm.caption(img, "image/png");

        assertEquals(1, model.calls.get(), "second caption of the same image should hit cache");
    }

    @Test
    void differentMime_isDistinctCacheKey() {
        CountingChatModel model = new CountingChatModel();
        DefaultVisionModel vm = new DefaultVisionModel(model, "describe", 1_000_000L, 16);
        byte[] img = {1, 2, 3, 4};

        vm.caption(img, "image/png");
        vm.caption(img, "image/jpeg");

        assertEquals(2, model.calls.get(), "different mime is a different key");
    }

    @Test
    void cacheDisabled_callsModelEachTime() {
        CountingChatModel model = new CountingChatModel();
        DefaultVisionModel vm = new DefaultVisionModel(model, "describe", 1_000_000L, 0);
        byte[] img = {1, 2, 3, 4};

        vm.caption(img, "image/png");
        vm.caption(img, "image/png");

        assertEquals(2, model.calls.get(), "cacheSize=0 disables caching");
    }

    @Test
    void answer_isNeverCached() {
        CountingChatModel model = new CountingChatModel();
        DefaultVisionModel vm = new DefaultVisionModel(model, "describe", 1_000_000L, 16);
        byte[] img = {1, 2, 3, 4};

        vm.answer(img, "image/png", "what is this?");
        vm.answer(img, "image/png", "what is this?");

        assertEquals(2, model.calls.get(), "answer varies by question, must not be cached");
    }

    @Test
    void oversizeImage_throws() {
        DefaultVisionModel vm = new DefaultVisionModel(new CountingChatModel(), "describe", 2L, 16);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> vm.caption(new byte[]{1, 2, 3}, "image/png"));
    }
}
