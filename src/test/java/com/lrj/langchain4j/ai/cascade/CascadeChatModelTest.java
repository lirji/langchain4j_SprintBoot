package com.lrj.langchain4j.ai.cascade;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 级联行为确定性单测：两个桩 ChatModel（不连真实模型），验证「低置信升级 / 高置信保留 / 指标计数」。
 */
class CascadeChatModelTest {

    /** 桩 ChatModel：固定返回一段文本，记录被调用次数。 */
    static final class StubModel implements ChatModel {
        final String reply;
        final AtomicInteger calls = new AtomicInteger();

        StubModel(String reply) { this.reply = reply; }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    private static ChatRequest req(String q) {
        return ChatRequest.builder().messages(UserMessage.from(q)).build();
    }

    private static CascadeProperties props() {
        return new CascadeProperties(); // 默认：启发式，min-answer-chars=8，self-rating 关
    }

    @Test
    void lowConfidenceCheapAnswer_escalatesToStrong() {
        StubModel cheap = new StubModel("我不确定，可能需要更多信息。");
        StubModel strong = new StubModel("这是强模型给出的确定答案，内容充分。");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        CascadeChatModel cascade = new CascadeChatModel(cheap, strong, new ConfidenceGate(props()), reg);

        CascadeChatModel.Outcome outcome = cascade.escalate(req("解释一下什么是级联"));

        assertEquals("strong", outcome.served());
        assertFalse(outcome.cheapConfident());
        assertEquals("这是强模型给出的确定答案，内容充分。", outcome.response().aiMessage().text());
        assertEquals(1, cheap.calls.get());
        assertEquals(1, strong.calls.get());
        assertEquals(1.0, reg.counter(CascadeChatModel.METRIC, "served", "strong").count());
        assertEquals(0.0, reg.counter(CascadeChatModel.METRIC, "served", "cheap").count());
    }

    @Test
    void highConfidenceCheapAnswer_staysCheap_strongNeverCalled() {
        StubModel cheap = new StubModel("级联是先用便宜模型作答、低置信才升级强模型的成本路由策略。");
        StubModel strong = new StubModel("不应被调用");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        CascadeChatModel cascade = new CascadeChatModel(cheap, strong, new ConfidenceGate(props()), reg);

        CascadeChatModel.Outcome outcome = cascade.escalate(req("什么是级联"));

        assertEquals("cheap", outcome.served());
        assertTrue(outcome.cheapConfident());
        assertEquals(1, cheap.calls.get());
        assertEquals(0, strong.calls.get(), "high-confidence 时强模型不应被调用");
        assertEquals(1.0, reg.counter(CascadeChatModel.METRIC, "served", "cheap").count());
        assertEquals(0.0, reg.counter(CascadeChatModel.METRIC, "served", "strong").count());
    }

    @Test
    void emptyOrTooShortCheapAnswer_escalates() {
        StubModel cheap = new StubModel("嗯");            // 短于 min-answer-chars
        StubModel strong = new StubModel("强模型的完整答案在此。");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        CascadeChatModel cascade = new CascadeChatModel(cheap, strong, new ConfidenceGate(props()), reg);

        assertEquals("strong", cascade.escalate(req("q")).served());
    }

    @Test
    void counterAccumulatesAcrossCalls() {
        StubModel cheapConfident = new StubModel("这是一段足够长且确定的答案内容。");
        StubModel strong = new StubModel("强答案");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        CascadeChatModel cascade = new CascadeChatModel(cheapConfident, strong, new ConfidenceGate(props()), reg);

        cascade.escalate(req("a"));
        cascade.escalate(req("b"));
        cascade.escalate(req("c"));

        assertEquals(3.0, reg.counter(CascadeChatModel.METRIC, "served", "cheap").count());
        assertEquals(0.0, reg.counter(CascadeChatModel.METRIC, "served", "strong").count());
    }

    @Test
    void serviceReturnsServedDetail() {
        StubModel cheap = new StubModel("不知道");                 // 命中不确定标记 → 升级
        StubModel strong = new StubModel("强模型给出的确定答案。");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        CascadeService svc = new CascadeService(
                new CascadeChatModel(cheap, strong, new ConfidenceGate(props()), reg));

        CascadeService.Result r = svc.ask("首都是哪里");
        assertEquals("strong", r.served());
        assertFalse(r.cheapConfident());
        assertEquals("强模型给出的确定答案。", r.answer());
    }

    @Test
    void nullRegistry_doesNotThrow() {
        StubModel cheap = new StubModel("这是一段足够长且确定的答案内容。");
        StubModel strong = new StubModel("x");
        CascadeChatModel cascade = new CascadeChatModel(cheap, strong, new ConfidenceGate(props()), null);
        assertEquals("cheap", cascade.escalate(req("q")).served());
        assertNull(null);
    }
}
